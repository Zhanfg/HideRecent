package top.gtian.hiderecent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic Launcher3/Quickstep recent-task filtering with OEM extensions.
 *
 * AOSP RecentTasksList is the portable launcher-side path. OPlus hooks remain as an additional
 * compatibility layer for ColorOS builds that bypass/replace the AOSP filtering path.
 */
object LauncherRecentHook {
    private const val TAG = "${Main.TAG}/launch"
    private const val SNAPSHOT_REFRESH_SEC = 60L

    private val hiddenSnapshot = HiddenPackagesSnapshot()
    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HideRecentTiles-launch-refresh").apply { isDaemon = true }
    }
    private val periodicStarted = AtomicBoolean(false)
    private val periodicExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HideRecentTiles-launch-periodic").apply { isDaemon = true }
    }

    @Volatile private var recentTasksRef: WeakReference<Any>? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var prefsReceiver: BroadcastReceiver? = null
    @Volatile private var receiverContext: Context? = null
    @Volatile private var prefsListener: ((Set<String>) -> Unit)? = null
    private var legacyDiagCount = 0
    private var groupedDiagCount = 0

    fun hook(module: Main, param: PackageLoadedParam) = hook(module, param.defaultClassLoader)

    fun hook(module: Main, loader: ClassLoader) {
        runCatching { hiddenSnapshot.replace(module.hiddenPackages) }
            .onFailure { module.log(Log.WARN, TAG, "initial snapshot failed: ${it.message}") }

        var count = 0
        count += hookAospRecentTasksList(module, loader) // portable Launcher3/Quickstep path
        count += hookFilterTaskInfo(module, loader)      // ColorOS 16+ enhancement
        count += hookFilterTask(module, loader)          // older ColorOS enhancement
        count += hookDiag(module, loader)
        count += hookRecentsLoad(module, loader)         // OPlus cache capture fallback

        if (count == 0) return

        registerPrefsListener(module)
        registerPrefsReceiver(module)
        startPeriodicRefresh(module)
        refreshSnapshotAsync(module, "init")
        module.log(Log.INFO, TAG, "recents-host hooks=$count")
    }


    /**
     * AOSP Launcher3 / Quickstep path, Android 10-current.
     *
     * RecentTasksList#loadTasksInBackground returns either ArrayList<Task> on older releases or
     * TaskLoadResult (an ArrayList<GroupTask> subclass) on newer releases. Mutating the returned
     * list in place preserves the private/vendor return subtype and avoids ClassCastException.
     */
    private fun hookAospRecentTasksList(module: Main, cl: ClassLoader): Int {
        val cls = loadFirstClass(
            cl,
            "com.android.quickstep.RecentTasksList",
            "com.android.quickstep.RecentTasksListImpl"
        ) ?: return 0

        var installed = 0
        val loadMethods = cls.declaredMethods.filter { m ->
            m.name == "loadTasksInBackground" &&
                java.util.List::class.java.isAssignableFrom(m.returnType)
        }
        loadMethods.forEachIndexed { index, method ->
            method.isAccessible = true
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setId("aosp/loadTasks/$index/${method.parameterCount}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    chain.thisObject?.let { recentTasksRef = WeakReference(it) }
                    val result = chain.proceed()
                    val hidden = hiddenSnapshot.get()
                    if (hidden.isNotEmpty()) {
                        val removed = RecentTaskListFilter.filterInPlace(result, hidden)
                        if (removed > 0) {
                            module.log(Log.INFO, TAG, "AOSP loadTasks filtered=$removed")
                        }
                    }
                    result
                }
            installed++
        }

        // Newer Launcher3 exposes a separate running-task list used by desktop/taskbar/quick-switch
        // surfaces. Return a filtered copy so we never mutate its internal backing state.
        val runningMethods = cls.declaredMethods.filter { m ->
            m.name == "getRunningTasks" && m.parameterCount == 0 &&
                java.util.List::class.java.isAssignableFrom(m.returnType)
        }
        runningMethods.forEachIndexed { index, method ->
            method.isAccessible = true
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setId("aosp/getRunningTasks/$index")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    chain.thisObject?.let { recentTasksRef = WeakReference(it) }
                    val result = chain.proceed()
                    val hidden = hiddenSnapshot.get()
                    if (hidden.isEmpty()) return@intercept result
                    val filtered = RecentTaskListFilter.filteredArrayList(result, hidden) ?: return@intercept result
                    val originalSize = (result as? Collection<*>)?.size
                    if (originalSize != null && filtered.size != originalSize) {
                        module.log(Log.INFO, TAG, "AOSP runningTasks filtered=${originalSize - filtered.size}")
                    }
                    filtered
                }
            installed++
        }

        if (installed > 0) {
            module.log(Log.INFO, TAG, "hooked generic AOSP RecentTasksList x$installed")
        }
        return installed
    }

    private fun loadFirstClass(cl: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            try { return cl.loadClass(name) } catch (_: Throwable) { }
        }
        return null
    }

    /** API 102 hot-reload cleanup. Old-generation callbacks must not keep the old classloader alive. */
    fun prepareHotReload(module: Main) {
        val ctx = receiverContext
        val receiver = prefsReceiver
        if (ctx != null && receiver != null) {
            runCatching { ctx.unregisterReceiver(receiver) }
                .onFailure { module.log(Log.WARN, TAG, "hot-reload unregister receiver failed: ${it.message}") }
        }
        prefsReceiver = null
        receiverContext = null
        prefsListener = null
        recentTasksRef = null
        runCatching { refreshExecutor.shutdownNow() }
        runCatching { periodicExecutor.shutdownNow() }
    }

    /**
     * ColorOS 16 path. Current OplusLauncher filters shell GroupedTaskInfo here.
     * true means the whole card is removed. Split/grouped cards are hidden when any member is hidden.
     */
    private fun hookFilterTaskInfo(module: Main, cl: ClassLoader): Int {
        val filterCls = try {
            cl.loadClass("com.oplus.quickstep.data.OplusRecentTasksFilter")
        } catch (_: Throwable) {
            return 0
        }

        val methods = filterCls.declaredMethods.filter { m ->
            m.name == "filterTaskInfo" &&
                (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.java) &&
                m.parameterTypes.any { it.name.contains("GroupedTaskInfo") || it.name.contains("GroupedRecentTaskInfo") }
        }
        if (methods.isEmpty()) return 0

        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setId("filterTaskInfo/$index/${method.parameterCount}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    HookDecision.evaluateOrProceed(
                        proceed = { chain.proceed() },
                        onError = { module.log(Log.WARN, TAG, "filterTaskInfo fallback: ${it.message}") }
                    ) {
                        val hidden = hiddenSnapshot.get()
                        if (hidden.isEmpty()) return@evaluateOrProceed null
                        val grouped = chain.args.firstOrNull {
                            (it?.javaClass?.name?.contains("GroupedTaskInfo") == true) ||
                                (it?.javaClass?.name?.contains("GroupedRecentTaskInfo") == true)
                        }
                        val packages = RecentTaskPackages.collect(grouped)
                        val hide = packages.any(hidden::contains)
                        val n = groupedDiagCount
                        if (n < 12) {
                            groupedDiagCount = n + 1
                            module.log(
                                Log.INFO, TAG,
                                "filterTaskInfo#$n pkgs=${packages.take(4)} hidden=${hidden.size} decision=${if (hide) "FILTER" else "KEEP"}"
                            )
                        }
                        if (hide) true else null
                    }
                }
        }
        module.log(Log.INFO, TAG, "hooked OplusRecentTasksFilter.filterTaskInfo x${methods.size}")
        return methods.size
    }

    /** Legacy ColorOS path: filterTask(GroupTask), true = remove card. */
    private fun hookFilterTask(module: Main, cl: ClassLoader): Int {
        val filterCls = try { cl.loadClass("com.oplus.quickstep.data.OplusRecentTasksFilter") }
        catch (_: Throwable) { return 0 }
        val methods = filterCls.declaredMethods.filter { m ->
            m.name == "filterTask" && m.parameterCount == 1 &&
                (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.java)
        }
        if (methods.isEmpty()) return 0

        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setId("filterTask/$index")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    HookDecision.evaluateOrProceed(
                        proceed = { chain.proceed() },
                        onError = { module.log(Log.WARN, TAG, "legacy filter fallback: ${it.message}") }
                    ) {
                        val hidden = hiddenSnapshot.get()
                        val group = chain.args.firstOrNull()
                        val packages = RecentTaskPackages.collect(group)
                        val hide = packages.any(hidden::contains)
                        val n = legacyDiagCount
                        if (n < 6) {
                            legacyDiagCount = n + 1
                            module.log(Log.INFO, TAG, "filterTask#$n pkgs=${packages.take(4)} hidden=${hidden.size}")
                        }
                        if (hide) true else null
                    }
                }
        }
        module.log(Log.INFO, TAG, "hooked OplusRecentTasksFilter.filterTask x${methods.size}")
        return methods.size
    }

    private fun hookDiag(module: Main, cl: ClassLoader): Int {
        val cls = try { cl.loadClass("com.android.quickstep.RecentsActivity") }
        catch (_: Throwable) { return 0 }
        val onCreate = try {
            cls.getDeclaredMethod("onCreate", Bundle::class.java).apply { isAccessible = true }
        } catch (_: Throwable) { return 0 }
        module.hook(onCreate)
            .setId("diag/RecentsActivity/onCreate")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                HookDecision.evaluateOrProceed(
                    proceed = { chain.proceed() },
                    onError = { module.log(Log.WARN, TAG, "diag fallback: ${it.message}") }
                ) {
                    registerPrefsReceiver(module)
                    refreshSnapshotAsync(module, "recents-onCreate")
                    null
                }
            }
        return 1
    }

    /**
     * Captures the live RecentTasksList object. It is then used to invalidate launcher task caches
     * immediately after a preference change, so an already-open Overview refreshes without restart.
     */
    private fun hookRecentsLoad(module: Main, cl: ClassLoader): Int {
        val cls = try { cl.loadClass("com.android.quickstep.OplusRecentTasksListImpl") }
        catch (_: Throwable) { return 0 }
        val methods = cls.declaredMethods.filter { it.name == "loadTasksInBackground" }
        if (methods.isEmpty()) return 0
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setId("recentsLoad/$index/${method.parameterCount}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    HookDecision.evaluateOrProceed(
                        proceed = { chain.proceed() },
                        onError = { module.log(Log.WARN, TAG, "recents-load fallback: ${it.message}") }
                    ) {
                        chain.thisObject?.let { recentTasksRef = WeakReference(it) }
                        registerPrefsReceiver(module)
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            refreshSnapshotAsync(module, "recents-load")
                        } else {
                            refreshSnapshotNow(module, "recents-load")
                        }
                        null
                    }
                }
        }
        module.log(Log.INFO, TAG, "hooked OplusRecentTasksListImpl.loadTasksInBackground x${methods.size}")
        return methods.size
    }

    private fun registerPrefsListener(module: Main) {
        if (prefsListener != null) return
        val listener: (Set<String>) -> Unit = { hidden ->
            hiddenSnapshot.replace(hidden)
            invalidateRecentsCache(module, "remote-prefs")
        }
        prefsListener = listener
        module.addHiddenListener(listener)
    }

    private fun registerPrefsReceiver(module: Main) {
        if (prefsReceiver != null) return
        val ctx = currentLauncherContext() ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val raw = i?.getStringExtra(Main.EXTRA_HIDE)
                if (raw != null) {
                    val changed = module.onPrefsPushed(raw)
                    // If the value is identical, the process listener deliberately does not fire;
                    // still invalidate once so reopening the module can be used as a manual reapply.
                    if (!changed) invalidateRecentsCache(module, "broadcast-reapply")
                } else {
                    refreshSnapshotAsync(module, "broadcast-no-extra", invalidate = true)
                }
            }
        }
        val ok = runCatching {
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(Main.ACTION_PREFS_CHANGED),
                Main.PREFS_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED
            )
            true
        }.getOrElse {
            module.log(Log.WARN, TAG, "registerReceiver failed: $it")
            false
        }
        if (ok) {
            prefsReceiver = receiver
            receiverContext = ctx
            module.log(Log.INFO, TAG, "prefs receiver registered")
        }
    }

    private fun invalidateRecentsCache(module: Main, reason: String) {
        val target = recentTasksRef?.get() ?: return
        val invalidate = Runnable {
            runCatching {
                val method = findNoArgMethod(target.javaClass, "onRecentTasksChanged")
                    ?: findNoArgMethod(target.javaClass, "invalidateLoadedTasks")
                    ?: return@Runnable
                method.invoke(target)
                module.log(Log.INFO, TAG, "recents cache invalidated($reason)")
            }.onFailure {
                module.log(Log.WARN, TAG, "recents cache invalidate failed($reason): ${it.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) invalidate.run() else mainHandler.post(invalidate)
    }

    private fun findNoArgMethod(start: Class<*>, name: String): Method? {
        var cls: Class<*>? = start
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (m != null) return m.apply { isAccessible = true }
            cls = cls.superclass
        }
        return null
    }

    private fun refreshSnapshotAsync(module: Main, reason: String, invalidate: Boolean = false) {
        runCatching {
            refreshExecutor.execute {
                refreshSnapshotNow(module, reason)
                if (invalidate) invalidateRecentsCache(module, reason)
            }
        }
    }

    private fun refreshSnapshotNow(module: Main, reason: String) {
        runCatching {
            hiddenSnapshot.replace(module.hiddenPackages)
            module.log(Log.INFO, TAG, "$reason refresh: ${hiddenSnapshot.get().size}")
        }.onFailure {
            module.log(Log.WARN, TAG, "snapshot refresh failed($reason): ${it.message}")
        }
    }

    private fun startPeriodicRefresh(module: Main) {
        if (!periodicStarted.compareAndSet(false, true)) return
        periodicExecutor.scheduleWithFixedDelay({
            refreshSnapshotNow(module, "periodic")
        }, SNAPSHOT_REFRESH_SEC, SNAPSHOT_REFRESH_SEC, TimeUnit.SECONDS)
    }

    private fun currentLauncherContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}

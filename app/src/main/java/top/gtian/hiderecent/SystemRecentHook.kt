package top.gtian.hiderecent

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic system_server recent-task filtering.
 *
 * The AOSP RecentTasks implementation is the primary cross-ROM layer. We hook both the visibility
 * predicate and the final recent-task list builder. OEM launcher-specific hooks are only fallback
 * layers when a vendor bypasses the standard ATMS recent-task result.
 */
object SystemRecentHook {
    private const val TAG = "${Main.TAG}/sys"
    private const val SNAPSHOT_REFRESH_SEC = 60L
    private val hiddenSnapshot = HiddenPackagesSnapshot()
    private val refreshStarted = AtomicBoolean(false)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HideRecentTiles-sys-refresh").apply { isDaemon = true }
    }
    @Volatile private var prefsListener: ((Set<String>) -> Unit)? = null
    private var listDiagCount = 0

    fun hook(module: Main, cl: ClassLoader) {
        val recentTasks = loadClass(
            cl,
            "com.android.server.wm.RecentTasks",
            "com.android.server.am.RecentTasks"
        ) ?: run {
            module.log(Log.ERROR, TAG, "AOSP RecentTasks not found")
            return
        }

        var count = 0
        // Android 10-current AOSP path. Some OEM releases carry extra parameters, so install both
        // common arities when present.
        count += hookBool(module, recentTasks, "isVisibleRecentTask", 1)
        count += hookBool(module, recentTasks, "isVisibleRecentTask", 2)

        // Stronger generic fallback: filter the final ArrayList<RecentTaskInfo>. This also catches
        // callers that reach RecentTasks but bypass/replace the visibility predicate internally.
        count += hookRecentTasksImpl(module, recentTasks)

        registerPrefsListener(module)
        runCatching { hiddenSnapshot.replace(module.hiddenPackages) }
            .onFailure { module.log(Log.WARN, TAG, "initial snapshot failed: ${it.message}") }
        startSnapshotRefresh(module)
        module.log(Log.INFO, TAG, "generic system recents hooks=$count")
    }

    fun prepareHotReload(module: Main) {
        prefsListener = null
        runCatching { refreshExecutor.shutdownNow() }
            .onFailure { module.log(Log.WARN, TAG, "hot-reload executor shutdown failed: ${it.message}") }
    }

    private fun registerPrefsListener(module: Main) {
        if (prefsListener != null) return
        val listener: (Set<String>) -> Unit = { hidden -> hiddenSnapshot.replace(hidden) }
        prefsListener = listener
        module.addHiddenListener(listener)
    }

    private fun hookBool(module: Main, cls: Class<*>, name: String, argCount: Int): Int {
        val method = findMethod(cls, name, argCount) ?: return 0
        runCatching { module.deoptimize(method) }
        module.hook(method)
            .setId("sys/$name/$argCount")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                HookDecision.evaluateOrProceed(
                    proceed = { chain.proceed() },
                    onError = { module.log(Log.WARN, TAG, "visibility hook fallback: ${it.message}") }
                ) {
                    val hidden = hiddenSnapshot.get()
                    if (hidden.isNotEmpty()) {
                        val task = chain.args.firstOrNull()
                        if (RecentTaskPackages.shouldHide(task, hidden)) {
                            return@evaluateOrProceed false
                        }
                    }
                    null
                }
            }
        return 1
    }

    private fun hookRecentTasksImpl(module: Main, cls: Class<*>): Int {
        val methods = cls.declaredMethods.filter { m ->
            m.name == "getRecentTasksImpl" &&
                java.util.List::class.java.isAssignableFrom(m.returnType)
        }
        if (methods.isEmpty()) return 0

        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setId("sys/getRecentTasksImpl/$index/${method.parameterCount}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val hidden = hiddenSnapshot.get()
                    if (hidden.isNotEmpty()) {
                        val removed = RecentTaskListFilter.filterInPlace(result, hidden)
                        val n = listDiagCount
                        if (removed > 0 || n < 4) {
                            listDiagCount = n + 1
                            module.log(
                                Log.INFO,
                                TAG,
                                "getRecentTasksImpl#$n removed=$removed hidden=${hidden.size}"
                            )
                        }
                    }
                    result
                }
        }
        return methods.size
    }

    private fun startSnapshotRefresh(module: Main) {
        if (!refreshStarted.compareAndSet(false, true)) return
        refreshExecutor.scheduleWithFixedDelay({
            runCatching { hiddenSnapshot.replace(module.hiddenPackages) }
                .onFailure { module.log(Log.WARN, TAG, "snapshot refresh failed: ${it.message}") }
        }, SNAPSHOT_REFRESH_SEC, SNAPSHOT_REFRESH_SEC, TimeUnit.SECONDS)
    }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? =
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == argCount }
            ?.apply { isAccessible = true }

    private fun loadClass(cl: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            try { return cl.loadClass(name) } catch (_: Throwable) { }
        }
        return null
    }
}

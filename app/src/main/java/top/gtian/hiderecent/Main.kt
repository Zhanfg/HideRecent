package top.gtian.hiderecent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.concurrent.CopyOnWriteArrayList

/** Modern Xposed API 102 entry point. */
class Main : XposedModule() {
    companion object {
        const val TAG = "HideRecentTiles"
        const val MODULE_PKG = "top.gtian.hiderecent"
        const val PREFS_NAME = "hide_recent"
        const val KEY_HIDE = "hide_list"
        const val ACTION_PREFS_CHANGED = "top.gtian.hiderecent.PREFS_CHANGED"
        const val EXTRA_HIDE = "hide_list"
        const val PREFS_PERMISSION = "top.gtian.hiderecent.permission.PREFS"

        private const val CACHE_TTL_MS = 1500L
        private const val DISK_CACHE_NAME = "hidden_cache.txt"

        /** Default launcher/recents hosts listed in scope.list. The runtime hook itself is generic. */
        val DEFAULT_RECENTS_HOST_PKGS = setOf(
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.systemui",
            "com.oplus.quickstep",
            "com.oplus.launcher"
        )

        fun notifyPrefsChanged(ctx: Context, hidden: Set<String>) {
            runCatching {
                ctx.sendBroadcast(
                    Intent(ACTION_PREFS_CHANGED).putExtra(EXTRA_HIDE, hidden.joinToString(","))
                )
            }.onFailure { Log.w(TAG, "notify prefs changed failed", it) }
        }
    }

    @Volatile private var cachedHidden: Set<String>? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var remotePrefsRef: SharedPreferences? = null
    @Volatile private var remotePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var diskCacheFileRef: java.io.File? = null

    private val hiddenListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    val hiddenPackages: Set<String>
        get() = readHiddenPackages()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded in ${param.processName}")
        // Framework-side RemotePreferences is the canonical cold-boot source.
        // Register the listener as early as possible so configuration changes are pushed into
        // launcher/system_server without waiting for polling.
        remotePrefs()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching { SystemRecentHook.hook(this, param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "system hook failed", it) }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage || param.packageName == MODULE_PKG) return
        // Only packages selected in LSPosed scope reach here. Do not hard-code ColorOS package
        // names: if the user scopes an OEM/AOSP recents host manually, probe it for the generic
        // Launcher3/Quickstep classes and install whatever compatible hooks are present.
        runCatching { LauncherRecentHook.hook(this, param) }
            .onFailure { log(Log.ERROR, TAG, "recents host hook failed", it) }
    }

    /** API 102 module-code hot reload. */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // Only classloader-neutral data may cross the reload boundary.
        param.savedInstanceState = cachedHidden?.joinToString(",") ?: ""
        runCatching { LauncherRecentHook.prepareHotReload(this) }
        runCatching { SystemRecentHook.prepareHotReload(this) }
        detachRemotePrefsListener()
        hiddenListeners.clear()
        log(Log.INFO, TAG, "hot reload: old generation detached")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        (param.savedInstanceState as? String)?.let { raw ->
            val restored = parse(raw)
            cachedHidden = restored
            cachedAt = SystemClock.elapsedRealtime()
        }
        remotePrefs()

        val oldHandles = param.oldHookHandles.toList()
        val loader = oldHandles.asSequence()
            .mapNotNull { it.executable.declaringClass.classLoader }
            .firstOrNull()
            ?: if (param.isSystemServer) ClassLoader.getSystemClassLoader()
            else currentContext()?.classLoader

        // Replace the complete hook generation atomically enough for API 102: first detach old
        // hook handles, then install the current implementation against the same target loader.
        oldHandles.forEach { runCatching { it.unhook() } }
        if (loader != null) {
            if (param.isSystemServer) {
                runCatching { SystemRecentHook.hook(this, loader) }
                    .onFailure { log(Log.ERROR, TAG, "hot reload system rehook failed", it) }
            } else if (param.processName.substringBefore(':') != MODULE_PKG) {
                runCatching { LauncherRecentHook.hook(this, loader) }
                    .onFailure { log(Log.ERROR, TAG, "hot reload recents-host rehook failed", it) }
            }
        } else {
            log(Log.ERROR, TAG, "hot reload: target classloader unavailable")
        }
        log(Log.INFO, TAG, "hot reload complete; oldHandles=${oldHandles.size}")
    }

    /** Register a process-local consumer for immediate configuration refresh. */
    fun addHiddenListener(listener: (Set<String>) -> Unit) {
        hiddenListeners += listener
        runCatching { listener(hiddenPackages) }
            .onFailure { log(Log.WARN, TAG, "initial hidden listener failed: ${it.message}") }
    }

    fun onPrefsPushed(raw: String?): Boolean = acceptFresh(parse(raw), "broadcast")

    private fun acceptFresh(next: Set<String>, source: String): Boolean {
        val previous = cachedHidden
        val changed = next != previous
        cachedHidden = next
        cachedAt = SystemClock.elapsedRealtime()
        if (changed) {
            persistDiskCache(next)
            log(Log.INFO, TAG, "hidden updated($source): ${next.size} ${next.take(5)}")
            hiddenListeners.forEach { listener ->
                runCatching { listener(next) }
                    .onFailure { log(Log.WARN, TAG, "hidden listener failed: ${it.message}") }
            }
        }
        return changed
    }

    /**
     * Read order is deliberate: framework RemotePreferences first. It is persistent across reboot,
     * does not require starting the module app, and is safe during system_server startup.
     */
    private fun readHiddenPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedHidden
        if (cached != null && now - cachedAt < CACHE_TTL_MS) return cached

        val fresh = queryRemote() ?: queryDiskCache()
        if (fresh != null) {
            if (fresh != cached) persistDiskCache(fresh)
            cachedHidden = fresh
            cachedAt = now
            return fresh
        }

        log(Log.WARN, TAG, "remote prefs/cache unavailable, keep cache(${cached?.size})")
        return cached ?: emptySet()
    }

    private fun queryRemote(): Set<String>? {
        return try {
            val prefs = remotePrefs() ?: return null
            val raw = prefs.getString(KEY_HIDE, null) ?: return null
            parse(raw)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "remote prefs failed: ${t.message}")
            null
        }
    }

    private fun persistDiskCache(hidden: Set<String>) {
        try {
            val target = diskCacheFile() ?: return
            val tmp = java.io.File(target.parentFile, "$DISK_CACHE_NAME.tmp")
            tmp.writeText(hidden.joinToString(","))
            if (!tmp.renameTo(target)) {
                target.writeText(hidden.joinToString(","))
                tmp.delete()
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "disk cache write failed: ${t.message}")
        }
    }

    private fun queryDiskCache(): Set<String>? {
        return try {
            val f = diskCacheFile() ?: return null
            if (!f.isFile) return null
            parse(f.readText())
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "disk cache read failed: ${t.message}")
            null
        }
    }

    private fun diskCacheFile(): java.io.File? {
        diskCacheFileRef?.let { return it }
        synchronized(this) {
            diskCacheFileRef?.let { return it }
            return try {
                currentContext()?.filesDir?.let { dir ->
                    java.io.File(dir, DISK_CACHE_NAME).also { diskCacheFileRef = it }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun remotePrefs(): SharedPreferences? {
        remotePrefsRef?.let { return it }
        return synchronized(this) {
            remotePrefsRef ?: try {
                getRemotePreferences(PREFS_NAME).also { prefs ->
                    remotePrefsRef = prefs
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                        if (key != null && key != KEY_HIDE) return@OnSharedPreferenceChangeListener
                        val raw = runCatching { changed.getString(KEY_HIDE, null) }.getOrNull() ?: return@OnSharedPreferenceChangeListener
                        acceptFresh(parse(raw), "remote-listener")
                    }
                    remotePrefsListener = listener
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "remote prefs init failed: ${t.message}")
                null
            }
        }
    }

    private fun detachRemotePrefsListener() {
        val prefs = remotePrefsRef
        val listener = remotePrefsListener
        if (prefs != null && listener != null) {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        remotePrefsListener = null
        remotePrefsRef = null
    }

    private fun parse(raw: String?): Set<String> =
        if (raw.isNullOrEmpty()) emptySet()
        else raw.split(',').filter { it.isNotEmpty() }.toSet()

    fun currentContext(): Context? = try {
        val cls = Class.forName("android.app.ActivityThread")
        cls.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}

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

/**
 * Launcher Stability module entry point.
 *
 * The legacy HideRecent implementation is intentionally not installed on this branch.
 * Compatibility helpers remain here so the old source set can still compile, but only
 * LauncherStabilityHook is active at runtime.
 */
class Main : XposedModule() {
    companion object {
        const val TAG = "LauncherStability"
        const val MODULE_PKG = "cc.axymorrsen.launcherstability"

        // Retained only for source compatibility with the original branch.
        const val PREFS_NAME = "hide_recent"
        const val KEY_HIDE = "hide_list"
        const val ACTION_PREFS_CHANGED = "cc.axymorrsen.launcherstability.PREFS_CHANGED"
        const val EXTRA_HIDE = "hide_list"
        const val PREFS_PERMISSION = "cc.axymorrsen.launcherstability.permission.PREFS"

        val DEFAULT_RECENTS_HOST_PKGS = setOf(
            "com.android.launcher",
            "com.oplus.quickstep",
            "com.oplus.launcher"
        )

        fun notifyPrefsChanged(ctx: Context, hidden: Set<String>) {
            runCatching {
                ctx.sendBroadcast(
                    Intent(ACTION_PREFS_CHANGED)
                        .putExtra(EXTRA_HIDE, hidden.joinToString(","))
                )
            }.onFailure { Log.w(TAG, "notify prefs changed failed", it) }
        }
    }

    @Volatile private var cachedHidden: Set<String> = emptySet()
    @Volatile private var cachedAt = 0L
    @Volatile private var hiddenPrefs: SharedPreferences? = null
    private val hiddenListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    val hiddenPackages: Set<String>
        get() {
            val now = SystemClock.elapsedRealtime()
            if (now - cachedAt < 1500L) return cachedHidden
            val next = runCatching {
                val raw = hiddenRemotePrefs().getString(KEY_HIDE, "") ?: ""
                parse(raw)
            }.getOrDefault(cachedHidden)
            cachedHidden = next
            cachedAt = now
            return next
        }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded in ${param.processName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // Deliberately empty: this branch never injects stability code into system_server.
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage || param.packageName == MODULE_PKG) return
        if (param.packageName !in DEFAULT_RECENTS_HOST_PKGS) return

        runCatching {
            LauncherStabilityHook.hook(this, param.defaultClassLoader)
        }.onFailure {
            log(Log.ERROR, TAG, "launcher stability hook failed", it)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState("")
        LauncherStabilityHook.prepareHotReload()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val oldHandles = param.oldHookHandles.toList()
        val loader = oldHandles.asSequence()
            .mapNotNull { it.executable.declaringClass.classLoader }
            .firstOrNull()

        oldHandles.forEach { runCatching { it.unhook() } }

        val processPackage = param.processName.substringBefore(':')
        if (!param.isSystemServer &&
            processPackage in DEFAULT_RECENTS_HOST_PKGS &&
            loader != null
        ) {
            runCatching {
                LauncherStabilityHook.hook(this, loader)
            }.onFailure {
                log(Log.ERROR, TAG, "hot reload rehook failed", it)
            }
        }
    }

    // Compatibility API retained for unreferenced legacy classes in this source tree.
    fun addHiddenListener(listener: (Set<String>) -> Unit) {
        hiddenListeners += listener
        runCatching { listener(hiddenPackages) }
    }

    fun onPrefsPushed(raw: String?): Boolean {
        val next = parse(raw)
        val changed = next != cachedHidden
        cachedHidden = next
        cachedAt = SystemClock.elapsedRealtime()
        if (changed) hiddenListeners.forEach { runCatching { it(next) } }
        return changed
    }

    private fun hiddenRemotePrefs(): SharedPreferences {
        hiddenPrefs?.let { return it }
        return getRemotePreferences(PREFS_NAME).also { hiddenPrefs = it }
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

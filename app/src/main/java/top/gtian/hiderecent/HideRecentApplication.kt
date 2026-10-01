package top.gtian.hiderecent

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-side bridge into libxposed's framework-owned RemotePreferences.
 *
 * The old implementation only wrote Android app SharedPreferences, while injected processes read
 * getRemotePreferences(). Those are different stores. This bridge mirrors the app setting into the
 * framework store and keeps it synchronized, which makes cold boot deterministic.
 */
class HideRecentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RemotePrefsSync.start(this)
    }
}

internal object RemotePrefsSync {
    private val listenerRegistered = AtomicBoolean(false)
    @Volatile private var service: XposedService? = null
    @Volatile private var localPrefs: SharedPreferences? = null
    @Volatile private var pendingRaw: String = ""

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == Main.KEY_HIDE) {
            pendingRaw = readLocalRaw(prefs)
            pushNow()
        }
    }

    fun start(app: Application) {
        val prefs = app.getSharedPreferences(Main.PREFS_NAME, Application.MODE_PRIVATE)
        localPrefs = prefs
        pendingRaw = readLocalRaw(prefs)
        prefs.registerOnSharedPreferenceChangeListener(localListener)

        if (!listenerRegistered.compareAndSet(false, true)) {
            pushNow()
            return
        }
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                pushNow()
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
            }
        })
    }

    private fun readLocalRaw(prefs: SharedPreferences): String {
        return try {
            prefs.getString(Main.KEY_HIDE, "") ?: ""
        } catch (_: ClassCastException) {
            // v26.9.x earlier builds briefly used StringSet. Preserve it through the upgrade.
            prefs.getStringSet(Main.KEY_HIDE, emptySet())?.joinToString(",").orEmpty()
        }
    }

    private fun pushNow() {
        val current = service ?: return
        val raw = pendingRaw
        runCatching {
            current.getRemotePreferences(Main.PREFS_NAME)
                .edit()
                .putString(Main.KEY_HIDE, raw)
                .apply()
        }.onFailure {
            Log.w(Main.TAG, "sync remote prefs failed", it)
        }
    }
}

package top.gtian.hiderecent

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

class LauncherStabilityApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LauncherRemotePrefsSync.start(this)
    }
}

internal object LauncherRemotePrefsSync {
    private val listenerRegistered = AtomicBoolean(false)
    @Volatile private var service: XposedService? = null
    @Volatile private var localPrefs: SharedPreferences? = null

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in LauncherStabilityPrefs.ALL_KEYS) pushNow()
    }

    fun start(app: Application) {
        val prefs = app.getSharedPreferences(
            LauncherStabilityPrefs.PREFS_NAME,
            Application.MODE_PRIVATE
        )
        localPrefs = prefs
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

    private fun pushNow() {
        val prefs = localPrefs ?: return
        val current = service ?: return
        runCatching {
            val editor = current
                .getRemotePreferences(LauncherStabilityPrefs.PREFS_NAME)
                .edit()

            LauncherStabilityPrefs.BOOLEAN_KEYS.forEach { key ->
                editor.putBoolean(key, prefs.getBoolean(key, false))
            }
            LauncherStabilityPrefs.STRING_KEYS.forEach { key ->
                editor.putString(key, prefs.getString(key, null))
            }
            LauncherStabilityPrefs.INT_KEYS.forEach { key ->
                editor.putInt(key, prefs.getInt(key, 0))
            }
            LauncherStabilityPrefs.FLOAT_KEYS.forEach { key ->
                editor.putFloat(key, prefs.getFloat(key, 0f))
            }
            editor.apply()
        }.onFailure {
            Log.w(Main.TAG, "launcher prefs sync failed", it)
        }
    }
}

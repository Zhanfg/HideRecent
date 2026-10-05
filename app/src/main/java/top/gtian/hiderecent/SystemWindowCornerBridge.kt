package top.gtian.hiderecent

import android.content.SharedPreferences
import android.content.res.Resources
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal system_server safety bridge for ColorOS 17 Flexible Window.
 *
 * The OEM service is always allowed to decide the radius first. We only provide a fallback
 * when FlexibleWindowManagerService.getCornerRadius(type) returns <= 0, which otherwise
 * produces a visibly square floating-window crop.
 *
 * No task policy, geometry, SurfaceControl transaction, split-screen state or resize path is
 * modified here.
 */
internal object SystemWindowCornerBridge {
    private const val TAG = "${Main.TAG}/windowCorner"
    private const val PREF_RETRY_MS = 1_500L
    private const val PREF_MAX_ATTEMPTS = 40
    private const val FALLBACK_RADIUS_DP = 28f

    private data class Config(
        val enabled: Boolean = false
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val bootstrapStarted = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val fallbackLogCount = AtomicLong(0)

    fun hook(module: Main, loader: ClassLoader) {
        if (!bootstrapStarted.compareAndSet(false, true)) return
        val expectedGeneration = generation.incrementAndGet()

        val count = hookCornerRadius(module, loader)
        module.log(
            if (count > 0) Log.INFO else Log.WARN,
            TAG,
            "system_server corner skeleton installed hooks=$count"
        )

        Thread({
            bootstrapPrefs(module, expectedGeneration)
        }, "LauncherStabilityCornerPrefs").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun prepareHotReload() {
        generation.incrementAndGet()
        bootstrapStarted.set(false)

        val prefs = remotePrefs
        val listener = prefsListener
        if (prefs != null && listener != null) {
            runCatching {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }

        remotePrefs = null
        prefsListener = null
        config = Config()
        fallbackLogCount.set(0L)
    }

    private fun bootstrapPrefs(
        module: Main,
        expectedGeneration: Int
    ) {
        // Keep RemotePreferences Binder work away from the system_server startup path.
        SystemClock.sleep(3_000L)

        for (attempt in 1..PREF_MAX_ATTEMPTS) {
            if (generation.get() != expectedGeneration) return

            val attached = runCatching {
                val prefs = module.getRemotePreferences(
                    LauncherStabilityPrefs.PREFS_NAME
                )
                refresh(prefs)

                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener {
                            changed,
                            key ->
                        if (key == null ||
                            key in LauncherStabilityPrefs.ALL_KEYS
                        ) {
                            refresh(changed)
                        }
                    }

                prefs.registerOnSharedPreferenceChangeListener(listener)
                remotePrefs = prefs
                prefsListener = listener
                true
            }.getOrElse {
                false
            }

            if (attached) {
                module.log(
                    Log.INFO,
                    TAG,
                    "remote prefs attached attempt=$attempt enabled=${config.enabled}"
                )
                return
            }

            SystemClock.sleep(PREF_RETRY_MS)
        }

        module.log(
            Log.WARN,
            TAG,
            "remote prefs unavailable; corner fallback stays disabled"
        )
    }

    private fun refresh(prefs: SharedPreferences) {
        config = Config(
            enabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FLEX_BRIDGE_ENABLED,
                false
            )
        )
    }

    private fun hookCornerRadius(
        module: Main,
        loader: ClassLoader
    ): Int {
        val cls = runCatching {
            Class.forName(
                "com.android.server.wm.FlexibleWindowManagerService",
                false,
                loader
            )
        }.getOrNull() ?: return 0

        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "getCornerRadius" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType)
                    ) &&
                    it.returnType == Int::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("coloros17/window/cornerFallback/$index")
                        .setExceptionMode(
                            XposedInterface.ExceptionMode.PROTECTIVE
                        )
                        .intercept { chain ->
                            val stock =
                                (chain.proceed() as? Number)?.toInt()
                                    ?: return@intercept 0

                            if (!config.enabled || stock > 0) {
                                return@intercept stock
                            }

                            val density =
                                Resources.getSystem()
                                    .displayMetrics
                                    .density
                                    .coerceAtLeast(1f)
                            val fallback =
                                (FALLBACK_RADIUS_DP * density)
                                    .toInt()
                                    .coerceAtLeast(1)

                            val n = fallbackLogCount.getAndIncrement()
                            if (n < 8L) {
                                module.log(
                                    Log.WARN,
                                    TAG,
                                    "OEM cornerRadius=$stock type=${chain.args.firstOrNull()} -> fallback=${fallback}px"
                                )
                            }

                            fallback
                        }
                    count++
                }.onFailure {
                    module.log(
                        Log.WARN,
                        TAG,
                        "corner hook failed: ${it.message}"
                    )
                }
            }

        return count
    }
}

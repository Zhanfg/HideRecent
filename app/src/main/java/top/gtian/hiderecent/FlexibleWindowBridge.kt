package top.gtian.hiderecent

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ColorOS 17 SmartSidebar request bridge.
 *
 * IMPORTANT:
 * FlexibleTaskView / pscanvas is intentionally untouched.
 *
 * ColorOS FlexibleTaskView.resize(Rect) is not a pure geometry setter: the OEM path also
 * participates in crop/corner-radius/SurfaceControl synchronization. Skipping an apparently
 * duplicate Rect can therefore leave a task with stale square corners. This bridge now only
 * filters accidental duplicate *entry requests* in SmartSidebar.
 */
internal object FlexibleWindowBridge {
    private const val TAG = "${Main.TAG}/flex"
    private const val PREF_RETRY_MS = 1_500L
    private const val PREF_MAX_ATTEMPTS = 40

    private data class Config(
        val enabled: Boolean = false,
        val zoomDebounce: Boolean = false,
        val splitDebounce: Boolean = false,
        val zoomDebounceMs: Int = 220,
        val splitDebounceMs: Int = 280
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val bootstrapStarted = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    @Volatile private var lastZoomKey: String? = null
    @Volatile private var lastZoomAt = 0L

    private val splitStartAt = Collections.synchronizedMap(
        WeakHashMap<Any, Long>()
    )

    fun hook(
        module: Main,
        packageName: String,
        loader: ClassLoader
    ) {
        if (packageName != Main.SMART_SIDEBAR_PKG) return
        if (!bootstrapStarted.compareAndSet(false, true)) return

        val currentGeneration = generation.incrementAndGet()
        val count = hookSmartSidebar(module, loader)

        module.log(
            if (count > 0) Log.INFO else Log.WARN,
            TAG,
            "SmartSidebar bridge skeleton installed hooks=$count"
        )

        Thread({
            bootstrapPrefs(module, currentGeneration)
        }, "LauncherStabilitySidebarPrefs").apply {
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

        lastZoomKey = null
        lastZoomAt = 0L
        splitStartAt.clear()
    }

    private fun bootstrapPrefs(
        module: Main,
        expectedGeneration: Int
    ) {
        SystemClock.sleep(750L)

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
                    "remote prefs attached attempt=$attempt"
                )
                return
            }

            SystemClock.sleep(PREF_RETRY_MS)
        }

        module.log(
            Log.WARN,
            TAG,
            "remote prefs unavailable; sidebar bridge remains pass-through"
        )
    }

    private fun refresh(prefs: SharedPreferences) {
        config = Config(
            enabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FLEX_BRIDGE_ENABLED,
                false
            ),
            zoomDebounce = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FLEX_ZOOM_DEBOUNCE,
                false
            ),
            splitDebounce = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FLEX_SPLIT_DEBOUNCE,
                false
            ),
            zoomDebounceMs = prefs.getInt(
                LauncherStabilityPrefs.KEY_FLEX_ZOOM_DEBOUNCE_MS,
                220
            ).coerceIn(80, 600),
            splitDebounceMs = prefs.getInt(
                LauncherStabilityPrefs.KEY_FLEX_SPLIT_DEBOUNCE_MS,
                280
            ).coerceIn(100, 800)
        )

        if (!config.enabled) {
            lastZoomKey = null
            lastZoomAt = 0L
            splitStartAt.clear()
        }
    }

    private fun hookSmartSidebar(
        module: Main,
        loader: ClassLoader
    ): Int {
        var count = 0

        loadClass(
            loader,
            "com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils"
        )?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "startActivityAsZoomWindow" &&
                        it.returnType == Void.TYPE
                }
                .forEachIndexed { index, method ->
                    count += hook(
                        module,
                        method,
                        "sidebar/zoom/$index"
                    ) { chain ->
                        if (!config.enabled ||
                            !config.zoomDebounce
                        ) {
                            return@hook chain.proceed()
                        }

                        val intent = chain.args
                            .firstOrNull { it is Intent } as? Intent
                            ?: return@hook chain.proceed()

                        val key = zoomRequestKey(intent)
                        val now = SystemClock.uptimeMillis()

                        synchronized(this) {
                            val previous = lastZoomKey
                            val delta = now - lastZoomAt

                            if (key == previous &&
                                delta >= 0L &&
                                delta < config.zoomDebounceMs
                            ) {
                                return@hook null
                            }

                            lastZoomKey = key
                            lastZoomAt = now
                        }

                        chain.proceed()
                    }
                }
        }

        loadClass(
            loader,
            "com.oplus.smartsidebar.panelview.edgepanel.utils.SplitScreenHandler"
        )?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "startSplitScreen" &&
                        it.returnType == Void.TYPE
                }
                .forEachIndexed { index, method ->
                    count += hook(
                        module,
                        method,
                        "sidebar/split/$index"
                    ) { chain ->
                        if (!config.enabled ||
                            !config.splitDebounce
                        ) {
                            return@hook chain.proceed()
                        }

                        val host = chain.thisObject
                            ?: return@hook chain.proceed()
                        val now = SystemClock.uptimeMillis()

                        synchronized(splitStartAt) {
                            val previous = splitStartAt[host]
                            if (previous != null) {
                                val delta = now - previous
                                if (delta >= 0L &&
                                    delta < config.splitDebounceMs
                                ) {
                                    return@hook null
                                }
                            }
                            splitStartAt[host] = now
                        }

                        chain.proceed()
                    }
                }
        }

        return count
    }

    private fun zoomRequestKey(intent: Intent): String {
        val component =
            intent.component?.flattenToShortString().orEmpty()
        val pkg = intent.`package`.orEmpty()
        val action = intent.action.orEmpty()
        val data = intent.dataString.orEmpty()

        val user = runCatching {
            intent.getIntExtra(
                "android.intent.extra.USER",
                Int.MIN_VALUE
            )
        }.getOrDefault(Int.MIN_VALUE)

        val source = runCatching {
            intent.getIntExtra(
                "multi_instance_source",
                Int.MIN_VALUE
            )
        }.getOrDefault(Int.MIN_VALUE)

        return "$component|$pkg|$action|$data|$user|$source"
    }

    private fun hook(
        module: Main,
        method: Method,
        id: String,
        body: (XposedInterface.Chain) -> Any?
    ): Int {
        method.isAccessible = true
        return runCatching {
            module.hook(method)
                .setId("coloros17/flex/$id")
                .setExceptionMode(
                    XposedInterface.ExceptionMode.PROTECTIVE
                )
                .intercept { chain -> body(chain) }
            1
        }.getOrElse {
            module.log(
                Log.WARN,
                TAG,
                "hook failed $id: ${it.message}"
            )
            0
        }
    }

    private fun loadClass(
        loader: ClassLoader,
        name: String
    ): Class<*>? =
        runCatching {
            Class.forName(
                name,
                false,
                loader
            )
        }.getOrNull()
}

package top.gtian.hiderecent

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ColorOS 17 Flexible Window bridge.
 *
 * Process isolation:
 * - com.coloros.smartsidebar: entry-point request de-duplication only.
 * - com.oplus.pscanvas: same-frame FlexibleTaskView.resize(Rect) de-duplication only.
 *
 * It intentionally does NOT inject into system_server and does NOT bypass OEM
 * compatibility/whitelist decisions. All functionality is default-off and fail-open.
 */
internal object FlexibleWindowBridge {
    private const val TAG = "${Main.TAG}/flex"
    private const val PREF_RETRY_MS = 1_500L
    private const val PREF_MAX_ATTEMPTS = 40

    private data class Config(
        val enabled: Boolean = false,
        val zoomDebounce: Boolean = false,
        val splitDebounce: Boolean = false,
        val resizeDedup: Boolean = false,
        val zoomDebounceMs: Int = 220,
        val splitDebounceMs: Int = 280,
        val resizeDedupMs: Int = 16
    )

    private data class ResizeStamp(
        val rect: Rect,
        val at: Long
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val bootstrapStarted = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    @Volatile private var lastZoomKey: String? = null
    @Volatile private var lastZoomAt = 0L

    private val splitStartAt = Collections.synchronizedMap(
        WeakHashMap<Any, Long>()
    )
    private val resizeStamps = Collections.synchronizedMap(
        WeakHashMap<Any, ResizeStamp>()
    )

    fun hook(
        module: Main,
        packageName: String,
        loader: ClassLoader
    ) {
        if (!bootstrapStarted.compareAndSet(false, true)) return
        val currentGeneration = generation.incrementAndGet()

        val count = when (packageName) {
            Main.SMART_SIDEBAR_PKG -> hookSmartSidebar(module, loader)
            Main.FLEXIBLE_WINDOW_UI_PKG -> hookFlexibleUi(module, loader)
            else -> 0
        }

        module.log(
            if (count > 0) Log.INFO else Log.WARN,
            TAG,
            "bridge skeleton installed package=$packageName hooks=$count"
        )

        Thread({
            bootstrapPrefs(module, currentGeneration)
        }, "LauncherStabilityFlexPrefs").apply {
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
        resizeStamps.clear()
    }

    private fun bootstrapPrefs(module: Main, expectedGeneration: Int) {
        SystemClock.sleep(750L)

        for (attempt in 1..PREF_MAX_ATTEMPTS) {
            if (generation.get() != expectedGeneration) return

            val attached = runCatching {
                val prefs = module.getRemotePreferences(
                    LauncherStabilityPrefs.PREFS_NAME
                )
                refresh(prefs)

                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                        if (key == null || key in LauncherStabilityPrefs.ALL_KEYS) {
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
            "remote prefs unavailable; bridge remains pass-through"
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
            resizeDedup = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FLEX_RESIZE_DEDUP,
                false
            ),
            zoomDebounceMs = prefs.getInt(
                LauncherStabilityPrefs.KEY_FLEX_ZOOM_DEBOUNCE_MS,
                220
            ).coerceIn(80, 600),
            splitDebounceMs = prefs.getInt(
                LauncherStabilityPrefs.KEY_FLEX_SPLIT_DEBOUNCE_MS,
                280
            ).coerceIn(100, 800),
            resizeDedupMs = prefs.getInt(
                LauncherStabilityPrefs.KEY_FLEX_RESIZE_DEDUP_MS,
                16
            ).coerceIn(4, 40)
        )

        if (!config.enabled) {
            lastZoomKey = null
            lastZoomAt = 0L
            splitStartAt.clear()
            resizeStamps.clear()
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
                        if (!config.enabled || !config.zoomDebounce) {
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
                        if (!config.enabled || !config.splitDebounce) {
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

    private fun hookFlexibleUi(
        module: Main,
        loader: ClassLoader
    ): Int {
        val cls = loadClass(
            loader,
            "com.oplus.flexiblewindow.FlexibleTaskView"
        ) ?: return 0

        var count = 0

        cls.declaredMethods
            .filter { method ->
                method.name == "resize" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.any {
                        it == Rect::class.java
                    }
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "pscanvas/resize/$index"
                ) { chain ->
                    if (!config.enabled || !config.resizeDedup) {
                        return@hook chain.proceed()
                    }

                    val host = chain.thisObject
                        ?: return@hook chain.proceed()
                    val rect = chain.args
                        .firstOrNull { it is Rect } as? Rect
                        ?: return@hook chain.proceed()

                    val now = SystemClock.uptimeMillis()
                    synchronized(resizeStamps) {
                        val previous = resizeStamps[host]
                        if (previous != null &&
                            previous.rect == rect
                        ) {
                            val delta = now - previous.at
                            if (delta >= 0L &&
                                delta <= config.resizeDedupMs
                            ) {
                                return@hook null
                            }
                        }
                    }

                    val result = chain.proceed()

                    synchronized(resizeStamps) {
                        resizeStamps[host] = ResizeStamp(
                            Rect(rect),
                            SystemClock.uptimeMillis()
                        )
                    }

                    result
                }
            }

        // Any surface/task lifecycle boundary invalidates geometry de-duplication state.
        cls.declaredMethods
            .filter {
                it.name in setOf(
                    "init",
                    "surfaceReplaced",
                    "release",
                    "onActivityResumed"
                )
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "pscanvas/reset/${method.name}/$index"
                ) { chain ->
                    chain.thisObject?.let { host ->
                        synchronized(resizeStamps) {
                            resizeStamps.remove(host)
                        }
                    }
                    chain.proceed()
                }
            }

        return count
    }

    private fun zoomRequestKey(intent: Intent): String {
        val component = intent.component?.flattenToShortString().orEmpty()
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
            Class.forName(name, false, loader)
        }.getOrNull()
}

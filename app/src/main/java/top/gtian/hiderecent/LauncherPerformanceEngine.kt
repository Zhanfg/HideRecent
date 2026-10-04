package top.gtian.hiderecent

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Low-overhead interaction/performance layer for ColorOS Launcher 17.3.12.
 *
 * Goals:
 * - improve perceived latency without forcing CPU/GPU frequencies;
 * - make fast recents gestures settle decisively while preserving slow precision;
 * - make cross-page icon dragging faster without changing workspace data/model code;
 * - keep all reflection cached after first resolution.
 *
 * Every feature defaults to disabled and all hooks fail open to OEM behavior.
 */
internal object LauncherPerformanceEngine {
    private const val TAG = "${Main.TAG}/perf"

    private data class Config(
        val enabled: Boolean = false,
        val adaptiveRecents: Boolean = false,
        val decisiveFling: Boolean = false,
        val workspaceDragPaging: Boolean = false,

        val oemAsyncTaskLaunch: Boolean = false,
        val oemInterruptSpring: Boolean = false,
        val oemAsyncSpringScroll: Boolean = false,
        val oemSwipeHomeSpring: Boolean = false,

        val recentsSettleFloor: Float = 0.64f,
        val flingGain: Float = 1.10f,
        val dragPageMultiplier: Float = 0.72f
    )

    @Volatile private var config = Config()
    @Volatile private var workspaceDragActive = false

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val missingFields = ConcurrentHashMap.newKeySet<String>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val missingMethods = ConcurrentHashMap.newKeySet<String>()

    fun refresh(prefs: SharedPreferences) {
        config = Config(
            enabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_ENGINE_ENABLED, false
            ),
            adaptiveRecents = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_ADAPTIVE_RECENTS, false
            ),
            decisiveFling = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_DECISIVE_FLING, false
            ),
            workspaceDragPaging = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_WORKSPACE_DRAG_PAGING, false
            ),

            oemAsyncTaskLaunch = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_OEM_ASYNC_TASK_LAUNCH, false
            ),
            oemInterruptSpring = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_OEM_INTERRUPT_SPRING, false
            ),
            oemAsyncSpringScroll = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_OEM_ASYNC_SPRING_SCROLL, false
            ),
            oemSwipeHomeSpring = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_PERF_OEM_SWIPE_HOME_SPRING, false
            ),

            recentsSettleFloor = prefs.getFloat(
                LauncherStabilityPrefs.KEY_PERF_RECENTS_SETTLE_FLOOR, 0.64f
            ).coerceIn(0.52f, 0.90f),
            flingGain = prefs.getFloat(
                LauncherStabilityPrefs.KEY_PERF_FLING_GAIN, 1.10f
            ).coerceIn(1.00f, 1.35f),
            dragPageMultiplier = prefs.getFloat(
                LauncherStabilityPrefs.KEY_PERF_DRAG_PAGE_MULTIPLIER, 0.72f
            ).coerceIn(0.55f, 1.00f)
        )

        if (!config.enabled || !config.workspaceDragPaging) {
            workspaceDragActive = false
        }
    }

    fun reset() {
        config = Config()
        workspaceDragActive = false
        fieldCache.clear()
        missingFields.clear()
        methodCache.clear()
        missingMethods.clear()
    }

    fun isAdaptiveRecentsActive(): Boolean =
        config.enabled && config.adaptiveRecents

    fun hook(module: Main, loader: ClassLoader): Int {
        var count = 0
        count += hookOemNativePerformance(module, loader)
        count += hookRecents(module, loader)
        count += hookWorkspaceDrag(module, loader)
        module.log(Log.INFO, TAG, "performance hooks installed=$count")
        return count
    }

    /**
     * Prefer OPlus' own hidden performance paths before custom tuning.
     *
     * All five methods are zero-argument boolean feature gates in clean Launcher 17.3.12.
     * Enabling them leaves implementation, interpolators, executors and lifecycle ownership
     * entirely to the OEM code.
     */
    private fun hookOemNativePerformance(
        module: Main,
        loader: ClassLoader
    ): Int {
        val cls = loadClass(
            loader,
            "com.android.common.util.AppFeatureUtils"
        ) ?: return 0

        val forceTrue = mapOf(
            "enableAsyncTaskViewLaunchWindowAnim" to
                { config.oemAsyncTaskLaunch },
            "enableTaskViewInterruptSpringAnim" to
                { config.oemInterruptSpring },
            "enableTaskWindowAsyncSpringScroll" to
                { config.oemAsyncSpringScroll },
            "isSupportRecentsSwipeUpWindowSpring" to
                { config.oemSwipeHomeSpring }
        )

        var count = 0

        cls.declaredMethods
            .filter {
                it.name in forceTrue.keys &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                val enabled = forceTrue.getValue(method.name)
                count += hook(
                    module,
                    method,
                    "oemNative/${method.name}/$index"
                ) { chain ->
                    if (config.enabled && enabled()) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            }

        // Some builds expose a negative gate in addition to the positive support gate.
        cls.declaredMethods
            .filter {
                it.name == "isRecentsSwipeUpWindowSpringDisable" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "oemNative/swipeHomeSpringDisable/$index"
                ) { chain ->
                    if (config.enabled && config.oemSwipeHomeSpring) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
            }

        return count
    }

    private fun hookRecents(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.oplus.quickstep.views.StackPagedViewEx")
            ?: return 0
        var count = 0

        cls.declaredMethods
            .filter {
                it.name == "getPageSnapAnimationDuration" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Int::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/adaptiveSnap/$index") { chain ->
                    val stock = (chain.proceed() as? Number)?.toInt() ?: 0
                    if (!config.enabled || !config.adaptiveRecents || stock <= 0) {
                        return@hook stock
                    }

                    val host = chain.thisObject ?: return@hook stock
                    val velocity = readScrollerVelocity(host) ?: 0f
                    val fast = readFloatField(host, "mFastFlingVelocity")
                        ?.takeIf { it > 1f } ?: 1f
                    val ratio = (abs(velocity) / fast).coerceIn(0f, 2.2f)

                    // Slow drag remains controlled; fast swipe progressively shortens settling.
                    val slowFactor = 0.88f
                    val t = ratio / 2.2f
                    val factor =
                        slowFactor - (slowFactor - config.recentsSettleFloor) * t

                    (stock * factor)
                        .roundToInt()
                        .coerceIn(minOf(80, stock), stock)
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "shouldFlingForVelocity" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType)
                    ) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/decisiveFling/$index") { chain ->
                    if (!config.enabled || !config.decisiveFling) {
                        return@hook chain.proceed()
                    }

                    val host = chain.thisObject ?: return@hook chain.proceed()
                    val velocity = (chain.args.firstOrNull() as? Number)?.toInt()
                        ?: return@hook chain.proceed()
                    val fast = readFloatField(host, "mFastFlingVelocity")
                        ?.takeIf { it > 1f }
                        ?: return@hook chain.proceed()

                    val absV = abs(velocity.toFloat())
                    val ratio = (absV / fast).coerceIn(0f, 2f)

                    val gain = when {
                        ratio >= 1f -> config.flingGain
                        ratio <= 0.30f -> 0.94f
                        else -> {
                            val local = (ratio - 0.30f) / 0.70f
                            0.94f + (1f - 0.94f) * local
                        }
                    }
                    val adjusted = (velocity * gain)
                        .roundToInt()
                        .coerceIn(-100_000, 100_000)

                    chain.proceed(arrayOf(adjusted))
                }
            }

        return count
    }

    private fun hookWorkspaceDrag(module: Main, loader: ClassLoader): Int {
        val workspace = loadClass(loader, "com.android.launcher3.Workspace")
            ?: return 0
        val pagedView = loadClass(loader, "com.android.launcher3.PagedView")

        var count = 0

        workspace.declaredMethods
            .filter { it.name == "onDragStart" }
            .forEachIndexed { index, method ->
                count += hook(module, method, "workspace/dragStart/$index") { chain ->
                    if (config.enabled && config.workspaceDragPaging) {
                        workspaceDragActive = true
                    }
                    chain.proceed()
                }
            }

        workspace.declaredMethods
            .filter { it.name == "onDragEnd" }
            .forEachIndexed { index, method ->
                count += hook(module, method, "workspace/dragEnd/$index") { chain ->
                    try {
                        chain.proceed()
                    } finally {
                        workspaceDragActive = false
                    }
                }
            }

        val durationMethods = LinkedHashSet<Method>()
        sequenceOf(workspace, pagedView)
            .filterNotNull()
            .forEach { cls ->
                cls.declaredMethods
                    .filter {
                        it.name in setOf(
                            "getPageSnapDuration",
                            "getPageSnapAnimationDuration"
                        ) &&
                            it.parameterTypes.isEmpty() &&
                            it.returnType == Int::class.javaPrimitiveType
                    }
                    .forEach(durationMethods::add)
            }

        durationMethods.forEachIndexed { index, method ->
            count += hook(module, method, "workspace/pageSnap/$index") { chain ->
                val stock = (chain.proceed() as? Number)?.toInt() ?: 0
                val host = chain.thisObject
                if (!config.enabled ||
                    !config.workspaceDragPaging ||
                    !workspaceDragActive ||
                    stock <= 0 ||
                    host == null ||
                    !workspace.isAssignableFrom(host.javaClass)
                ) {
                    return@hook stock
                }

                (stock * config.dragPageMultiplier)
                    .roundToInt()
                    .coerceIn(minOf(70, stock), stock)
            }
        }

        return count
    }

    private fun readScrollerVelocity(host: Any): Float? {
        val scroller = readField(host, "mScroller") ?: return null
        val method = findNoArgMethod(scroller.javaClass, "getCurrVelocity")
            ?: return null
        return runCatching {
            (method.invoke(scroller) as? Number)?.toFloat()
        }.getOrNull()
    }

    private fun readFloatField(host: Any, name: String): Float? =
        (readField(host, name) as? Number)?.toFloat()

    private fun readField(host: Any, name: String): Any? {
        val cls = host.javaClass
        val key = "${cls.name}#$name"
        if (missingFields.contains(key)) return null

        val field = fieldCache[key] ?: findFieldDeep(cls, name)?.also {
            fieldCache[key] = it
        } ?: run {
            missingFields += key
            return null
        }

        return runCatching { field.get(host) }.getOrNull()
    }

    private fun findFieldDeep(start: Class<*>, name: String): Field? {
        var cls: Class<*>? = start
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findNoArgMethod(start: Class<*>, name: String): Method? {
        val key = "${start.name}#$name()"
        if (missingMethods.contains(key)) return null

        val cached = methodCache[key]
        if (cached != null) return cached

        var cls: Class<*>? = start
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }
            if (method != null) {
                method.isAccessible = true
                methodCache[key] = method
                return method
            }
            cls = cls.superclass
        }

        missingMethods += key
        return null
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
                .setId("launcher17312/perf/$id")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> body(chain) }
            1
        }.getOrElse {
            module.log(Log.WARN, TAG, "hook failed $id: ${it.message}")
            0
        }
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }.getOrNull()
}

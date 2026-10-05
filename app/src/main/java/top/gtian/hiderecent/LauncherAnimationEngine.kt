package top.gtian.hiderecent

import android.animation.Animator
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Experimental animation layer for ColorOS Launcher 17.3.12.
 *
 * Safety rules:
 * - Never writes SurfaceControl.Transaction matrices.
 * - Never touches RectTransformHelper internals or remote-window crop state.
 * - All hooks are installed once but are pure pass-through unless the master toggle is enabled.
 * - Every modified argument/result is clamped to a conservative range.
 * - View transforms are restored to their original values when the feature is disabled.
 */
internal object LauncherAnimationEngine {
    private const val TAG = "${Main.TAG}/anim"

    private data class Config(
        val enabled: Boolean = false,
        val iconPulse: Boolean = false,
        val iconTilt: Boolean = false,
        val recentsTilt: Boolean = false,
        val springTuning: Boolean = false,
        val snapTuning: Boolean = false,
        val overscrollTuning: Boolean = false,
        val flingTuning: Boolean = false,
        val runningScale: Boolean = false,
        val transitionTiming: Boolean = false,

        val iconPulseScale: Float = 0.94f,
        val iconTiltDeg: Float = 4f,
        val recentsTiltDeg: Float = 6f,
        val springStiffness: Float = 1f,
        val springDamping: Float = 1f,
        val snapMultiplier: Float = 1f,
        val overscrollMultiplier: Float = 1f,
        val flingMultiplier: Float = 1f,
        val runningScaleValue: Float = 1f,
        val transitionMultiplier: Float = 1f
    )

    @Volatile private var config = Config()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val tiltedViews = CopyOnWriteArrayList<WeakReference<View>>()
    private val originalRotationY = Collections.synchronizedMap(
        WeakHashMap<View, Float>()
    )
    private val originalCameraDistance = Collections.synchronizedMap(
        WeakHashMap<View, Float>()
    )
    private val lastAppliedRotationY = Collections.synchronizedMap(
        WeakHashMap<View, Float>()
    )

    private val noArgMethodCache = ConcurrentHashMap<String, Method>()
    private val singleFloatMethodCache = ConcurrentHashMap<String, Method>()
    private val missingMethods = ConcurrentHashMap.newKeySet<String>()

    fun refresh(prefs: SharedPreferences) {
        config = Config(
            enabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_ENGINE_ENABLED, false
            ),
            iconPulse = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_ICON_PULSE, false
            ),
            iconTilt = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_ICON_TILT, false
            ),
            recentsTilt = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_RECENTS_TILT, false
            ),
            springTuning = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_SPRING_TUNING, false
            ),
            snapTuning = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_SNAP_TUNING, false
            ),
            overscrollTuning = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_OVERSCROLL_TUNING, false
            ),
            flingTuning = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_FLING_TUNING, false
            ),
            runningScale = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_RUNNING_SCALE, false
            ),
            transitionTiming = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ANIM_TRANSITION_TIMING, false
            ),

            iconPulseScale = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_ICON_PULSE_SCALE, 0.94f
            ).coerceIn(0.88f, 1f),
            iconTiltDeg = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_ICON_TILT_DEG, 4f
            ).coerceIn(0f, 12f),
            recentsTiltDeg = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_RECENTS_TILT_DEG, 6f
            ).coerceIn(0f, 16f),
            springStiffness = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_SPRING_STIFFNESS, 1f
            ).coerceIn(0.65f, 1.35f),
            springDamping = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_SPRING_DAMPING, 1f
            ).coerceIn(0.75f, 1.25f),
            snapMultiplier = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_SNAP_MULTIPLIER, 1f
            ).coerceIn(0.65f, 1.45f),
            overscrollMultiplier = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_OVERSCROLL_MULTIPLIER, 1f
            ).coerceIn(0.55f, 1.45f),
            flingMultiplier = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_FLING_MULTIPLIER, 1f
            ).coerceIn(0.65f, 1.50f),
            runningScaleValue = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_RUNNING_SCALE_VALUE, 1f
            ).coerceIn(0.90f, 1.06f),
            transitionMultiplier = prefs.getFloat(
                LauncherStabilityPrefs.KEY_ANIM_TRANSITION_MULTIPLIER, 1f
            ).coerceIn(0.70f, 1.35f)
        )

        if (!config.enabled || !config.recentsTilt) {
            restoreTrackedTiltViews()
        }
    }

    fun reset() {
        config = Config()
        restoreTrackedTiltViews()
        tiltedViews.clear()
        originalRotationY.clear()
        originalCameraDistance.clear()
        lastAppliedRotationY.clear()
        noArgMethodCache.clear()
        singleFloatMethodCache.clear()
        missingMethods.clear()
    }

    fun hook(module: Main, loader: ClassLoader): Int {
        var count = 0
        count += hookSourceIconMotion(module, loader)
        count += hookTransitionAnimatorTiming(module, loader)
        count += hookRecentsMotion(module, loader)

        module.log(Log.INFO, TAG, "animation hooks installed=$count")
        return count
    }

    /**
     * App open/close companion motion.
     *
     * This intentionally animates the Launcher source icon only. It gives app opening/closing
     * a physical press/release and 3D lean without touching remote-window SurfaceControl state.
     */
    private fun hookSourceIconMotion(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(
            loader,
            "com.android.launcher3.anim.OplusLauncherAppTransitionHelper"
        ) ?: return 0

        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "startAppLaunchWindowAnim" ||
                    it.name == "startAppCloseWindowAnim" ||
                    it.name == "startTaskViewLaunchWindowAnim"
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "source/${method.name}/$index") { chain ->
                    if (!config.enabled || (!config.iconPulse && !config.iconTilt)) {
                        return@hook chain.proceed()
                    }

                    val source = findSourceView(chain.args)
                    if (source != null) {
                        val closeLike = method.name.contains("Close", ignoreCase = true)
                        applyIconImpulse(source, closeLike)
                    }

                    chain.proceed()
                }
            }

        return count
    }

    /**
     * OPlus animation-set wrapper receives Android Animator instances through k()/l().
     * Duration scaling here naturally covers APP_LAUNCH, APP_CLOSE, BREAK_APP_OPEN,
     * TASK_VIEW_LAUNCH and related paths while preserving the OEM interpolators themselves.
     */
    private fun hookTransitionAnimatorTiming(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "sa.m0") ?: return 0
        var count = 0

        cls.declaredMethods
            .filter {
                it.name in setOf("k", "l") &&
                    it.parameterTypes.isNotEmpty() &&
                    Animator::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "timing/${method.name}/$index") { chain ->
                    if (!config.enabled || !config.transitionTiming) {
                        return@hook chain.proceed()
                    }

                    val animator = chain.args.firstOrNull() as? Animator
                    if (animator != null && shouldRetuneAnimationSet(chain.thisObject)) {
                        val old = animator.duration
                        if (old >= 0L) {
                            animator.duration =
                                (old * config.transitionMultiplier)
                                    .roundToInt()
                                    .toLong()
                                    .coerceIn(40L, 1200L)
                        }
                    }

                    chain.proceed()
                }
            }

        return count
    }

    private fun shouldRetuneAnimationSet(host: Any?): Boolean {
        if (host == null) return true
        val type = invokeNoArgDeep(host, "e")
            ?.toString()
            ?.uppercase()
            ?: return true

        return type.contains("APP") ||
            type.contains("BREAK") ||
            type.contains("SWIPE") ||
            type.contains("HOME") ||
            type.contains("TASK_VIEW")
    }

    private fun hookRecentsMotion(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.oplus.quickstep.views.StackPagedViewEx")
            ?: return 0
        var count = 0

        cls.declaredMethods
            .filter {
                it.name == "updateScaleDimAlpha" &&
                    it.parameterTypes.isNotEmpty() &&
                    View::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/tilt/$index") { chain ->
                    val result = chain.proceed()
                    val view = chain.args.firstOrNull() as? View
                    if (view != null) applyRecentsTilt(view)
                    result
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "getPageSnapAnimationDuration" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Int::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/snap/$index") { chain ->
                    val stock = (chain.proceed() as? Number)?.toInt() ?: 0
                    if (!config.enabled ||
                        !config.snapTuning ||
                        LauncherPerformanceEngine.isAdaptiveRecentsActive() ||
                        stock <= 0
                    ) {
                        stock
                    } else {
                        (stock * config.snapMultiplier)
                            .roundToInt()
                            .coerceIn(100, 900)
                    }
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "getTargetRunningTaskScale" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/runningScale/$index") { chain ->
                    val stock = (chain.proceed() as? Number)?.toFloat() ?: 1f
                    if (!config.enabled || !config.runningScale) {
                        stock
                    } else {
                        (stock * config.runningScaleValue).coerceIn(0.75f, 1.10f)
                    }
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "getSmoothOverScrollAmount" &&
                    it.parameterTypes.size == 1 &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/overscroll/$index") { chain ->
                    val stock = (chain.proceed() as? Number)?.toFloat() ?: 0f
                    if (!config.enabled || !config.overscrollTuning) {
                        stock
                    } else {
                        stock * config.overscrollMultiplier
                    }
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
                count += hook(module, method, "recents/fling/$index") { chain ->
                    if (!config.enabled || !config.flingTuning) {
                        return@hook chain.proceed()
                    }

                    val velocity = (chain.args.firstOrNull() as? Number)?.toInt()
                        ?: return@hook chain.proceed()
                    val adjusted = (velocity * config.flingMultiplier)
                        .roundToInt()
                        .coerceIn(-100_000, 100_000)
                    chain.proceed(arrayOf(adjusted))
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "applyMoveSpringConfig" &&
                    it.parameterTypes.size == 2
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/spring/$index") { chain ->
                    val result = chain.proceed()
                    if (config.enabled && config.springTuning) {
                        val springAnimation = chain.args.firstOrNull()
                        retuneAndroidXSpring(springAnimation)
                    }
                    result
                }
            }

        return count
    }

    private fun retuneAndroidXSpring(animation: Any?) {
        if (animation == null) return
        runCatching {
            val spring = invokeNoArgDeep(animation, "getSpring") ?: return
            val stiffness = (invokeNoArgDeep(spring, "getStiffness") as? Number)
                ?.toFloat()
            val damping = (invokeNoArgDeep(spring, "getDampingRatio") as? Number)
                ?.toFloat()

            if (stiffness != null && stiffness.isFinite() && stiffness > 0f) {
                invokeSingleFloatDeep(
                    spring,
                    "setStiffness",
                    (stiffness * config.springStiffness).coerceIn(50f, 10_000f)
                )
            }
            if (damping != null && damping.isFinite() && damping > 0f) {
                invokeSingleFloatDeep(
                    spring,
                    "setDampingRatio",
                    (damping * config.springDamping).coerceIn(0.15f, 2.5f)
                )
            }
        }
    }

    private fun applyIconImpulse(view: View, closeLike: Boolean) {
        if (!view.isAttachedToWindow) return

        val baseScaleX = view.scaleX
        val baseScaleY = view.scaleY
        val baseRotationY = view.rotationY

        val targetScale =
            if (config.iconPulse) config.iconPulseScale else 1f
        val targetTilt =
            if (config.iconTilt) config.iconTiltDeg * if (closeLike) -1f else 1f
            else 0f

        view.animate().cancel()
        view.animate()
            .scaleX(baseScaleX * targetScale)
            .scaleY(baseScaleY * targetScale)
            .rotationY(baseRotationY + targetTilt)
            .setDuration(55L)
            .withEndAction {
                if (!view.isAttachedToWindow) return@withEndAction
                view.animate()
                    .scaleX(baseScaleX)
                    .scaleY(baseScaleY)
                    .rotationY(baseRotationY)
                    .setDuration(110L)
                    .start()
            }
            .start()
    }

    private fun applyRecentsTilt(view: View) {
        if (!config.enabled || !config.recentsTilt) {
            restoreTilt(view)
            return
        }

        val parent = view.parent as? ViewGroup ?: return
        if (parent.width <= 0 || view.width <= 0) return

        synchronized(originalRotationY) {
            if (!originalRotationY.containsKey(view)) {
                originalRotationY[view] = view.rotationY
                originalCameraDistance[view] = view.cameraDistance
                tiltedViews += WeakReference(view)
            }
        }

        val parentCenter = parent.width / 2f
        val viewCenter = view.x + view.width / 2f
        val normalized = ((viewCenter - parentCenter) / parent.width)
            .coerceIn(-1f, 1f)

        val base = originalRotationY[view] ?: 0f
        val targetRotation = base - normalized * config.recentsTiltDeg
        val last = lastAppliedRotationY[view]

        // updateScaleDimAlpha() is a per-frame hot path. Avoid redundant property writes.
        if (last == null || abs(last - targetRotation) >= 0.08f) {
            view.rotationY = targetRotation
            lastAppliedRotationY[view] = targetRotation
        }

        val density = view.resources.displayMetrics.density
        val targetCameraDistance = 14_000f * density
        if (abs(view.cameraDistance - targetCameraDistance) >= 1f) {
            view.cameraDistance = targetCameraDistance
        }
    }

    private fun restoreTilt(view: View) {
        synchronized(originalRotationY) {
            val rotation = originalRotationY.remove(view)
            val distance = originalCameraDistance.remove(view)
            lastAppliedRotationY.remove(view)
            if (rotation != null && abs(view.rotationY - rotation) >= 0.01f) {
                view.rotationY = rotation
            }
            if (distance != null && abs(view.cameraDistance - distance) >= 1f) {
                view.cameraDistance = distance
            }
        }
    }

    private fun restoreTrackedTiltViews() {
        val iterator = tiltedViews.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next().get() ?: continue
            mainHandler.post { restoreTilt(view) }
        }
    }

    private fun findSourceView(args: List<Any?>): View? =
        args.firstOrNull { it is View } as? View

    private fun hook(
        module: Main,
        method: Method,
        id: String,
        body: (XposedInterface.Chain) -> Any?
    ): Int {
        method.isAccessible = true
        return runCatching {
            module.hook(method)
                .setId("launcher17312/anim/$id")
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

    private fun invokeNoArgDeep(host: Any, name: String): Any? {
        val start = host.javaClass
        val key = "${start.name}#$name()"
        if (missingMethods.contains(key)) return null

        val method = noArgMethodCache[key] ?: run {
            var cls: Class<*>? = start
            var found: Method? = null
            while (cls != null && found == null) {
                found = cls.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                }
                cls = cls.superclass
            }
            if (found == null) {
                missingMethods += key
                return null
            }
            found.isAccessible = true
            noArgMethodCache[key] = found
            found
        }

        return runCatching { method.invoke(host) }.getOrNull()
    }

    private fun invokeSingleFloatDeep(host: Any, name: String, value: Float): Any? {
        val start = host.javaClass
        val key = "${start.name}#$name(float)"
        if (missingMethods.contains(key)) return null

        val method = singleFloatMethodCache[key] ?: run {
            var cls: Class<*>? = start
            var found: Method? = null
            while (cls != null && found == null) {
                found = cls.declaredMethods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Float::class.javaPrimitiveType
                }
                cls = cls.superclass
            }
            if (found == null) {
                missingMethods += key
                return null
            }
            found.isAccessible = true
            singleFloatMethodCache[key] = found
            found
        }

        return runCatching { method.invoke(host, value) }.getOrNull()
    }
}

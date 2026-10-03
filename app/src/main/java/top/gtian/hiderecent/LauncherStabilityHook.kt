package top.gtian.hiderecent

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Non-invasive ColorOS Launcher 17.3.12 hooks.
 *
 * Design rules:
 * 1. Never touch SurfaceControl / RectTransformHelper / Spring animation hot paths.
 * 2. Use stable OEM view APIs that exist in the clean 17.3.12 APK.
 * 3. Fail closed when legacy ZuyQA repack classes are detected.
 * 4. Haptics only run on event boundaries and are rate-limited.
 * 5. Every feature defaults to false.
 */
object LauncherStabilityHook {
    private const val TAG = "${Main.TAG}/stability"

    private const val PAGE_HAPTIC_GAP_MS = 65L
    private const val ACTION_HAPTIC_GAP_MS = 120L
    private const val STRONG_HAPTIC_GAP_MS = 220L

    private data class Config(
        val hapticEffects: Boolean = false,
        val hideTaskTitle: Boolean = false,
        val hideTaskIcon: Boolean = false,
        val hideClearButton: Boolean = false
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var lastPage = Int.MIN_VALUE
    @Volatile private var lastPageHapticAt = 0L
    @Volatile private var lastActionHapticAt = 0L
    @Volatile private var lastStrongHapticAt = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val headers = CopyOnWriteArrayList<WeakReference<Any>>()
    private val clearPanels = CopyOnWriteArrayList<WeakReference<Any>>()

    private val originalVisibility = Collections.synchronizedMap(
        WeakHashMap<View, Int>()
    )

    private val legacyMarkers = arrayOf(
        "com.android.quickstep.util.animation.ZuyqaWindowTilt",
        "com.android.quickstep.util.animation.ZuyqaLayerGeometry",
        "com.android.common.util.ZuyqaAnimationTuning",
        "com.android.common.util.ZuyqaFeaturePrefs",
        "hi.ZuyqaAppLaunch1739",
        "hi.ZuyqaCloseVariant1739",
        "hi.ZuyqaSwipeHome1739"
    )

    fun hook(module: Main, loader: ClassLoader) {
        val foundLegacy = legacyMarkers.filter { name ->
            runCatching { loader.loadClass(name) }.isSuccess
        }
        if (foundLegacy.isNotEmpty()) {
            module.log(
                Log.ERROR,
                TAG,
                "legacy repack detected; refusing to inject: ${foundLegacy.joinToString()}"
            )
            return
        }

        attachPrefs(module)

        var installed = 0
        installed += hookTaskHeader(module, loader)
        installed += hookClearPanel(module, loader)
        installed += hookHaptics(module, loader)

        module.log(
            if (installed > 0) Log.INFO else Log.WARN,
            TAG,
            "installed hooks=$installed config=$config"
        )
    }

    fun prepareHotReload() {
        val p = remotePrefs
        val l = prefsListener
        if (p != null && l != null) {
            runCatching { p.unregisterOnSharedPreferenceChangeListener(l) }
        }
        remotePrefs = null
        prefsListener = null
        headers.clear()
        clearPanels.clear()
        originalVisibility.clear()
        lastPage = Int.MIN_VALUE
        lastPageHapticAt = 0L
        lastActionHapticAt = 0L
        lastStrongHapticAt = 0L
    }

    private fun attachPrefs(module: Main) {
        if (remotePrefs != null) return
        val prefs = runCatching {
            module.getRemotePreferences(LauncherStabilityPrefs.PREFS_NAME)
        }.getOrElse {
            module.log(Log.WARN, TAG, "remote prefs unavailable: ${it.message}")
            null
        } ?: return

        remotePrefs = prefs
        refreshConfig(prefs)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == null || LauncherStabilityPrefs.BOOLEAN_KEYS.contains(key)) {
                refreshConfig(changed)
                refreshTrackedViews()
            }
        }
        prefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun refreshConfig(prefs: SharedPreferences) {
        config = Config(
            hapticEffects = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HAPTIC_EFFECTS, false
            ),
            hideTaskTitle = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_TASK_TITLE, false
            ),
            hideTaskIcon = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_TASK_ICON, false
            ),
            hideClearButton = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_CLEAR_BUTTON, false
            )
        )
    }

    private fun hookTaskHeader(module: Main, loader: ClassLoader): Int {
        val cls = runCatching {
            loader.loadClass("com.oplus.quickstep.views.OplusTaskHeaderView")
        }.getOrNull() ?: return 0

        val names = setOf(
            "onFinishInflate",
            "reset",
            "setTitle",
            "setTitleTv",
            "setTitleWithLayoutCheck",
            "setIcon",
            "setTaskIcon"
        )

        var count = 0
        cls.declaredMethods
            .filter { it.name in names }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/header/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            chain.thisObject?.let { host ->
                                track(headers, host)
                                applyHeader(host)
                            }
                            result
                        }
                    count++
                }.onFailure {
                    module.log(
                        Log.WARN,
                        TAG,
                        "header hook failed ${method.name}: ${it.message}"
                    )
                }
            }

        return count
    }

    private fun hookClearPanel(module: Main, loader: ClassLoader): Int {
        val cls = runCatching {
            loader.loadClass("com.oplus.quickstep.views.OplusClearAllPanelView")
        }.getOrNull() ?: return 0

        val names = setOf(
            "onFinishInflate",
            "setContentAlpha",
            "setVisibilityAlpha",
            "setViewClickable",
            "onConfigurationChanged"
        )

        var count = 0
        cls.declaredMethods
            .filter { it.name in names }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/clear/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            chain.thisObject?.let { host ->
                                track(clearPanels, host)
                                applyClearPanel(host)
                            }
                            result
                        }
                    count++
                }.onFailure {
                    module.log(
                        Log.WARN,
                        TAG,
                        "clear hook failed ${method.name}: ${it.message}"
                    )
                }
            }

        return count
    }

    /**
     * Haptic layer.
     *
     * The clean 17.3.12 launcher already exposes the OEM haptic path through
     * OplusRecentsViewImpl/View.performHapticFeedback(). We only add calls at semantic event
     * boundaries. No vibrator service, waveform, animation frame callback, or Surface hook is used.
     */
    private fun hookHaptics(module: Main, loader: ClassLoader): Int {
        val cls = runCatching {
            loader.loadClass("com.android.quickstep.views.OplusRecentsViewImpl")
        }.getOrNull() ?: return 0

        var count = 0

        // A light detent when the centered task page actually changes.
        cls.declaredMethods
            .filter { method ->
                method.name == "notifyPageSwitchListener" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/page/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (config.hapticEffects) {
                                val page = (chain.args.getOrNull(0) as? Number)?.toInt()
                                if (page != null && page != lastPage) {
                                    lastPage = page
                                    firePageHaptic(chain.thisObject, chain.args)
                                }
                            }
                            result
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "page haptic hook failed: ${it.message}")
                }
            }

        // Swipe-to-dismiss: trigger once when the dismissal animation is created.
        val dismissNames = setOf(
            "createTaskDismissAnimation",
            "createTaskDismissAnimationAsStack"
        )
        cls.declaredMethods
            .filter { it.name in dismissNames }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/dismiss/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (config.hapticEffects) {
                                fireActionHaptic(
                                    chain.thisObject,
                                    chain.args,
                                    HapticFeedbackConstants.CONFIRM
                                )
                            }
                            result
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "dismiss haptic hook failed: ${it.message}")
                }
            }

        // Clear-all is intentionally stronger and separately rate-limited.
        cls.declaredMethods
            .filter { it.name == "dismissAllTasks" }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/clearAll/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (config.hapticEffects) {
                                fireStrongHaptic(
                                    chain.thisObject,
                                    chain.args,
                                    HapticFeedbackConstants.CONFIRM
                                )
                            }
                            chain.proceed()
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "clear-all haptic hook failed: ${it.message}")
                }
            }

        // Opening a task from overview: a short confirmation at the action boundary.
        cls.declaredMethods
            .filter { it.name == "launchTasksAnimatedCompat" }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/launch/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (config.hapticEffects) {
                                fireActionHaptic(
                                    chain.thisObject,
                                    chain.args,
                                    HapticFeedbackConstants.GESTURE_END
                                )
                            }
                            chain.proceed()
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "launch haptic hook failed: ${it.message}")
                }
            }

        // Horizontal interaction start and returning home get paired gesture boundary feedback.
        cls.declaredMethods
            .filter {
                (it.name == "onScrollInteractionBegin" || it.name == "startHomeFromRecents") &&
                    it.parameterTypes.isEmpty()
            }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/gesture/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (config.hapticEffects) {
                                val type = if (method.name == "onScrollInteractionBegin") {
                                    HapticFeedbackConstants.GESTURE_START
                                } else {
                                    HapticFeedbackConstants.GESTURE_END
                                }
                                fireActionHaptic(chain.thisObject, chain.args, type)
                            }
                            chain.proceed()
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "gesture haptic hook failed: ${it.message}")
                }
            }

        return count
    }

    private fun firePageHaptic(host: Any?, args: Array<Any?>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPageHapticAt < PAGE_HAPTIC_GAP_MS) return
        lastPageHapticAt = now
        performHaptic(host, args, HapticFeedbackConstants.SEGMENT_TICK)
    }

    private fun fireActionHaptic(host: Any?, args: Array<Any?>, type: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionHapticAt < ACTION_HAPTIC_GAP_MS) return
        lastActionHapticAt = now
        performHaptic(host, args, type)
    }

    private fun fireStrongHaptic(host: Any?, args: Array<Any?>, type: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStrongHapticAt < STRONG_HAPTIC_GAP_MS) return
        lastStrongHapticAt = now

        val view = findHapticView(host, args) ?: return
        // Two confirmation impulses with a short spacing feel more deliberate than a long buzz,
        // while still using the OEM haptic pipeline and system vibration policy.
        performHapticOnView(view, type)
        mainHandler.postDelayed(
            { if (config.hapticEffects && view.isAttachedToWindow) performHapticOnView(view, type) },
            55L
        )
    }

    private fun performHaptic(host: Any?, args: Array<Any?>, type: Int): Boolean {
        val view = findHapticView(host, args) ?: return false
        return performHapticOnView(view, type)
    }

    private fun performHapticOnView(view: View, type: Int): Boolean {
        return runCatching {
            // First use the exact launcher/View policy. If the host disabled only its local
            // haptic flag, ignore that local flag on the second try but still keep global policy.
            view.performHapticFeedback(type) ||
                view.performHapticFeedback(
                    type,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
        }.getOrDefault(false)
    }

    private fun findHapticView(host: Any?, args: Array<Any?>): View? {
        if (host is View) return host

        args.firstOrNull { it is View }?.let { return it as View }

        if (host != null) {
            (invokeNoArg(host, "getCurrentPageOrExtra") as? View)?.let { return it }
            (invokeNoArg(host, "getFocusedTaskView") as? View)?.let { return it }
        }
        return null
    }

    private fun applyHeader(host: Any) {
        val current = config

        val title = invokeNoArg(host, "getTitleTv") as? View
        if (title != null) setHiddenPreservingState(title, current.hideTaskTitle)

        val icon = invokeNoArg(host, "getTaskIcon") as? View
        if (icon != null) setHiddenPreservingState(icon, current.hideTaskIcon)
    }

    private fun applyClearPanel(host: Any) {
        val view = host as? View ?: return
        setHiddenPreservingState(view, config.hideClearButton)
        if (config.hideClearButton) view.isClickable = false
    }

    private fun setHiddenPreservingState(view: View, hide: Boolean) {
        synchronized(originalVisibility) {
            if (hide) {
                if (!originalVisibility.containsKey(view)) {
                    originalVisibility[view] = view.visibility
                }
                if (view.visibility == View.VISIBLE) {
                    view.visibility = View.INVISIBLE
                }
            } else {
                val original = originalVisibility.remove(view)
                if (original != null && view.visibility != original) {
                    view.visibility = original
                }
            }
        }
    }

    private fun invokeNoArg(host: Any, name: String): Any? = runCatching {
        val method = host.javaClass.getMethod(name)
        method.isAccessible = true
        method.invoke(host)
    }.getOrNull()

    private fun track(list: CopyOnWriteArrayList<WeakReference<Any>>, host: Any) {
        var exists = false
        list.removeAll { ref ->
            val value = ref.get()
            if (value == null) true
            else {
                if (value === host) exists = true
                false
            }
        }
        if (!exists) list += WeakReference(host)
    }

    private fun refreshTrackedViews() {
        val action = Runnable {
            refreshList(headers) { applyHeader(it) }
            refreshList(clearPanels) { applyClearPanel(it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private fun refreshList(
        list: CopyOnWriteArrayList<WeakReference<Any>>,
        apply: (Any) -> Unit
    ) {
        list.removeAll { ref ->
            val value = ref.get()
            if (value == null) true
            else {
                runCatching { apply(value) }
                false
            }
        }
    }
}

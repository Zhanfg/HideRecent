package top.gtian.hiderecent

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
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

    private data class Config(
        val hapticEffects: Boolean = false,
        val hideTaskTitle: Boolean = false,
        val hideTaskIcon: Boolean = false,
        val hideClearButton: Boolean = false
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var lastDismissStartHapticAt = 0L

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
        lastDismissStartHapticAt = 0L
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
     * Exact runtime equivalent of the ColorOS 16/17 Smali scroll-haptic patch.
     *
     * Upstream computeScrollHelper() calls needVibrateWhenScroll() before its OEM haptic branch.
     * The community patch replaces that boolean gate with:
     *
     *   abs(mScroller.getCurrVelocity()) > mFastFlingVelocity
     *
     * Hooking needVibrateWhenScroll() to return the same predicate preserves the rest of
     * computeScrollHelper(), including OPlus' own motor effect, debounce and global settings.
     */
    private fun hookHaptics(module: Main, loader: ClassLoader): Int {
        val cls = runCatching {
            loader.loadClass("com.android.quickstep.views.OplusRecentsViewImpl")
        }.getOrNull() ?: return 0

        val clearAllHaptic = runCatching {
            loader.loadClass("com.android.common.util.ya")
                .declaredMethods
                .firstOrNull { method ->
                    method.name == "e" &&
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                Context::class.java,
                                Int::class.javaPrimitiveType,
                                Long::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                            )
                        )
                }
                ?.apply { isAccessible = true }
        }.getOrNull()

        val appFeatureUtils = runCatching {
            val featureCls = loader.loadClass("com.android.common.util.AppFeatureUtils")
            val instance = featureCls.getField("INSTANCE").get(null)
            val method = featureCls.getMethod("isSupportKillProgramWave")
            Pair(instance, method)
        }.getOrNull()

        var count = 0

        // 1) Horizontal stacked-recents scrolling: preserve the proven hotfix3 gate.
        cls.declaredMethods
            .filter {
                it.name == "needVibrateWhenScroll" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/nativeScrollGate/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (!config.hapticEffects) return@intercept chain.proceed()
                            val host = chain.thisObject ?: return@intercept chain.proceed()
                            val velocity = readScrollerVelocity(host)
                                ?: return@intercept chain.proceed()
                            val fastFling = readFloatField(host, "mFastFlingVelocity")
                                ?: return@intercept chain.proceed()
                            kotlin.math.abs(velocity) > fastFling
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "native scroll-haptic hook failed: ${it.message}")
                }
            }

        // 2) Swipe-up dismiss START: immediate Clear All-class impulse.
        //    This is intentionally before chain.proceed() so the motor starts with the animation.
        val dismissStartNames = setOf(
            "createTaskDismissAnimation",
            "createTaskDismissAnimationAsStack"
        )
        cls.declaredMethods
            .filter { it.name in dismissStartNames }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/dismissStart/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (config.hapticEffects) {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastDismissStartHapticAt >= 90L) {
                                    lastDismissStartHapticAt = now
                                    val context = (chain.thisObject as? View)?.context
                                    if (context != null && clearAllHaptic != null) {
                                        performClearAllHaptic(
                                            module, context, clearAllHaptic, appFeatureUtils
                                        )
                                    }
                                }
                            }
                            chain.proceed()
                        }
                    count++
                }.onFailure {
                    module.log(
                        Log.WARN,
                        TAG,
                        "dismiss-start haptic hook failed ${method.name}: ${it.message}"
                    )
                }
            }

        module.log(
            Log.INFO,
            TAG,
            "native haptic hooks installed x$count; clearAll=${clearAllHaptic != null}"
        )
        return count
    }

    private fun performClearAllHaptic(
        module: Main,
        context: Context,
        clearAllHaptic: java.lang.reflect.Method,
        appFeatureUtils: Pair<Any, java.lang.reflect.Method>?
    ) {
        runCatching {
            val supportKillWave = appFeatureUtils?.let { (instance, method) ->
                (method.invoke(instance) as? Boolean) == true
            } ?: false
            val waveId = if (supportKillWave) 472 else 50
            clearAllHaptic.invoke(null, context, waveId, 0L, 12)
        }.onFailure {
            module.log(Log.WARN, TAG, "clear-all haptic failed: ${it.message}")
        }
    }
    private fun readScrollerVelocity(host: Any): Float? {
        val scroller = readField(host, "mScroller") ?: return null
        val method = scroller.javaClass.methods.firstOrNull {
            it.name == "getCurrVelocity" &&
                it.parameterTypes.isEmpty()
        } ?: scroller.javaClass.declaredMethods.firstOrNull {
            it.name == "getCurrVelocity" &&
                it.parameterTypes.isEmpty()
        } ?: return null

        return runCatching {
            method.isAccessible = true
            (method.invoke(scroller) as? Number)?.toFloat()
        }.getOrNull()
    }

    private fun readFloatField(host: Any, name: String): Float? {
        val value = readField(host, name) ?: return null
        return (value as? Number)?.toFloat()
    }

    private fun readField(host: Any, name: String): Any? {
        var cls: Class<*>? = host.javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(host)
                }.getOrNull()
            }
            cls = cls.superclass
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

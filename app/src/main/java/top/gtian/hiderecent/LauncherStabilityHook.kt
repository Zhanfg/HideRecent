package top.gtian.hiderecent

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private const val BOOT_MIN_UPTIME_MS = 15_000L
    private const val APP_READY_GRACE_MS = 1_500L
    private const val PREF_RETRY_MS = 1_500L
    private const val PREF_MAX_ATTEMPTS = 8

    private val bootstrapStarted = AtomicBoolean(false)
    private val hooksInstalled = AtomicBoolean(false)
    private val bootstrapGeneration = AtomicInteger(0)

    private data class Config(
        val hapticEffects: Boolean = false,
        val dismissProfile: LauncherHapticProfile = LauncherHapticProfile.OEM_CLEAR_ALL,
        val clearAllProfile: LauncherHapticProfile = LauncherHapticProfile.OEM_CLEAR_ALL,
        val recentsEnterProfile: LauncherHapticProfile = LauncherHapticProfile.NONE,
        val hideWorkspaceLabels: Boolean = false,
        val hideDrawerLabels: Boolean = false,
        val hidePageIndicator: Boolean = false,
        val hideBottomSearch: Boolean = false,
        val hideTaskTitle: Boolean = false,
        val hideTaskIcon: Boolean = false,
        val hideClearButton: Boolean = false
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var lastDismissStartHapticAt = 0L
    @Volatile private var lastRecentsEnterHapticAt = 0L
    private val bypassClearAllOverride = ThreadLocal<Boolean>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val headers = CopyOnWriteArrayList<WeakReference<Any>>()
    private val clearPanels = CopyOnWriteArrayList<WeakReference<Any>>()
    private val bubbleLabels = CopyOnWriteArrayList<WeakReference<Any>>()
    private val pageIndicators = CopyOnWriteArrayList<WeakReference<Any>>()
    private val bottomSearchViews = CopyOnWriteArrayList<WeakReference<Any>>()

    private val originalVisibility = Collections.synchronizedMap(
        WeakHashMap<View, Int>()
    )
    private val originalTextPaintAlpha = Collections.synchronizedMap(
        WeakHashMap<TextView, Int>()
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

    /**
     * Cold-boot invariant: never perform Binder/config I/O or hook registration on
     * PackageLoaded's critical thread. Launcher/Quickstep must reach stock-ready state first.
     */
    fun hook(module: Main, loader: ClassLoader) {
        if (!bootstrapStarted.compareAndSet(false, true)) return
        val generation = bootstrapGeneration.incrementAndGet()

        Thread({
            bootSafeBootstrap(module, loader, generation)
        }, "LauncherStabilityInit").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun bootSafeBootstrap(module: Main, loader: ClassLoader, generation: Int) {
        try {
            // Do not touch Launcher during the first seconds of Android boot.
            val remaining = BOOT_MIN_UPTIME_MS - SystemClock.elapsedRealtime()
            if (remaining > 0L) SystemClock.sleep(remaining)

            // Wait until the Launcher Application exists and credential-encrypted user storage
            // is unlocked. Before this point the module remains a complete no-op.
            var context: Context? = null
            while (generation == bootstrapGeneration.get()) {
                context = module.currentContext()
                if (context != null) {
                    val userManager = context.getSystemService(UserManager::class.java)
                    if (userManager == null || userManager.isUserUnlocked) break
                }
                SystemClock.sleep(250L)
            }
            if (generation != bootstrapGeneration.get()) return

            SystemClock.sleep(APP_READY_GRACE_MS)
            if (generation != bootstrapGeneration.get()) return

            // RemotePreferences creation performs Binder I/O. Keep every attempt off the
            // Launcher main thread; failure leaves the process 100% stock and retries later.
            var attached = false
            for (attempt in 1..PREF_MAX_ATTEMPTS) {
                if (generation != bootstrapGeneration.get()) return
                attached = attachPrefs(module)
                if (attached) break
                module.log(Log.WARN, TAG, "prefs attach attempt $attempt failed; staying stock")
                SystemClock.sleep(PREF_RETRY_MS)
            }
            if (!attached || generation != bootstrapGeneration.get()) {
                module.log(Log.ERROR, TAG, "boot-safe init gave up; Launcher remains stock")
                return
            }

            // Hook registration itself is deferred until the Launcher main queue is idle.
            // This avoids racing class loading / first layout even after preferences are ready.
            mainHandler.post {
                if (generation != bootstrapGeneration.get()) return@post
                Looper.myQueue().addIdleHandler {
                    if (generation == bootstrapGeneration.get() &&
                        hooksInstalled.compareAndSet(false, true)
                    ) {
                        runCatching {
                            installHooks(module, loader)
                        }.onFailure {
                            hooksInstalled.set(false)
                            module.log(
                                Log.ERROR,
                                TAG,
                                "idle hook install failed; Launcher remains stock",
                                it
                            )
                        }
                    }
                    false
                }
            }
        } catch (t: Throwable) {
            // Fail open: never let module initialization take Launcher down.
            module.log(Log.ERROR, TAG, "boot-safe init failed; Launcher remains stock", t)
        }
    }

    private fun installHooks(module: Main, loader: ClassLoader) {
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

        var installed = 0
        installed += hookTaskHeader(module, loader)
        installed += hookClearPanel(module, loader)
        installed += hookDesktopPresentation(module, loader)
        installed += hookHaptics(module, loader)

        module.log(
            if (installed > 0) Log.INFO else Log.WARN,
            TAG,
            "boot-safe hooks installed=$installed config=$config"
        )
    }
    fun prepareHotReload() {
        bootstrapGeneration.incrementAndGet()
        bootstrapStarted.set(false)
        hooksInstalled.set(false)
        val p = remotePrefs
        val l = prefsListener
        if (p != null && l != null) {
            runCatching { p.unregisterOnSharedPreferenceChangeListener(l) }
        }
        remotePrefs = null
        prefsListener = null
        config = Config()
        headers.clear()
        clearPanels.clear()
        bubbleLabels.clear()
        pageIndicators.clear()
        bottomSearchViews.clear()
        originalVisibility.clear()
        originalTextPaintAlpha.clear()
        lastDismissStartHapticAt = 0L
        lastRecentsEnterHapticAt = 0L
    }

    private fun attachPrefs(module: Main): Boolean {
        if (remotePrefs != null) return true
        val prefs = runCatching {
            module.getRemotePreferences(LauncherStabilityPrefs.PREFS_NAME)
        }.getOrElse {
            module.log(Log.WARN, TAG, "remote prefs unavailable: ${it.message}")
            null
        } ?: return false

        remotePrefs = prefs
        refreshConfig(prefs)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == null || LauncherStabilityPrefs.ALL_KEYS.contains(key)) {
                refreshConfig(changed)
                refreshTrackedViews()
            }
        }
        prefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return true
    }
    private fun refreshConfig(prefs: SharedPreferences) {
        config = Config(
            hapticEffects = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HAPTIC_EFFECTS, false
            ),
            dismissProfile = LauncherHapticProfile.fromPref(
                prefs.getString(
                    LauncherStabilityPrefs.KEY_DISMISS_HAPTIC_PROFILE,
                    LauncherHapticProfile.OEM_CLEAR_ALL.prefValue
                )
            ),
            clearAllProfile = LauncherHapticProfile.fromPref(
                prefs.getString(
                    LauncherStabilityPrefs.KEY_CLEAR_ALL_HAPTIC_PROFILE,
                    LauncherHapticProfile.OEM_CLEAR_ALL.prefValue
                )
            ),
            recentsEnterProfile = LauncherHapticProfile.fromPref(
                prefs.getString(
                    LauncherStabilityPrefs.KEY_RECENTS_ENTER_HAPTIC_PROFILE,
                    LauncherHapticProfile.NONE.prefValue
                )
            ),
            hideWorkspaceLabels = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_WORKSPACE_LABELS, false
            ),
            hideDrawerLabels = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_DRAWER_LABELS, false
            ),
            hidePageIndicator = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_PAGE_INDICATOR, false
            ),
            hideBottomSearch = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_BOTTOM_SEARCH, false
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

    private fun hookDesktopPresentation(module: Main, loader: ClassLoader): Int {
        var count = 0
        count += hookBubbleLabels(module, loader)
        count += hookPageIndicator(module, loader)
        count += hookBottomSearch(module, loader)
        return count
    }

    private fun hookBubbleLabels(module: Main, loader: ClassLoader): Int {
        val cls = runCatching {
            loader.loadClass("com.android.launcher3.OplusBubbleTextView")
        }.getOrNull() ?: return 0

        var count = 0
        val names = setOf(
            "updateCustomizeAppTitle",
            "resetViewProperties",
            "updateTextSize",
            "updateViewsForScale"
        )
        cls.declaredMethods
            .filter { it.name in names }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/labels/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            chain.thisObject?.let { host ->
                                track(bubbleLabels, host)
                                applyBubbleLabel(host)
                            }
                            result
                        }
                    count++
                }
            }
        return count
    }

    private fun hookPageIndicator(module: Main, loader: ClassLoader): Int {
        val cls = runCatching {
            loader.loadClass("com.android.launcher.pageindicators.OplusPageIndicator")
        }.getOrNull() ?: return 0

        var count = 0
        val names = setOf(
            "initViewState",
            "setActivePage",
            "updateActiveIndex",
            "updatePressEffectForState",
            "updateBackgroundRect"
        )
        cls.declaredMethods
            .filter { it.name in names }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/pageIndicator/${method.name}/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            chain.thisObject?.let { host ->
                                track(pageIndicators, host)
                                applyPageIndicator(host)
                            }
                            result
                        }
                    count++
                }
            }
        return count
    }

    private fun hookBottomSearch(module: Main, loader: ClassLoader): Int {
        val classes = listOf(
            "com.android.launcher.bottomsearch.BottomSearchBoxContainerView",
            "com.android.launcher3.qsb.QsbWidgetHostView"
        ).mapNotNull { runCatching { loader.loadClass(it) }.getOrNull() }

        var count = 0
        classes.forEach { cls ->
            val names = setOf(
                "onAddChildView",
                "initCompactNoTitleStyle",
                "initCompactStyle",
                "convertToCompactNoTitle",
                "convertToCompactStyle",
                "convertToSpreadStyle",
                "getDefaultView"
            )
            cls.declaredMethods
                .filter { it.name in names }
                .forEachIndexed { index, method ->
                    method.isAccessible = true
                    runCatching {
                        module.hook(method)
                            .setId("launcher17312/bottomSearch/${cls.simpleName}/${method.name}/$index")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val result = chain.proceed()
                                chain.thisObject?.let { host ->
                                    track(bottomSearchViews, host)
                                    applyBottomSearch(host)
                                }
                                if (result is View) {
                                    track(bottomSearchViews, result)
                                    applyBottomSearch(result)
                                }
                                result
                            }
                        count++
                    }
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

        val hapticClass = runCatching {
            loader.loadClass("com.android.common.util.ya")
        }.getOrNull()

        val clearAllHaptic = hapticClass?.declaredMethods?.firstOrNull { method ->
            method.name == "e" &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Context::class.java,
                        Int::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                )
        }?.apply { isAccessible = true }

        val appFeatureUtils = runCatching {
            val featureCls = loader.loadClass("com.android.common.util.AppFeatureUtils")
            val instance = featureCls.getField("INSTANCE").get(null)
            val method = featureCls.getMethod("isSupportKillProgramWave")
            Pair(instance, method)
        }.getOrNull()

        var count = 0

        // Horizontal stacked-recents scrolling: keep the proven ColorOS-native gate.
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
                    module.log(Log.WARN, TAG, "scroll-haptic hook failed: ${it.message}")
                }
            }

        // Single-card swipe-up dismiss: selected profile fires immediately at animation creation.
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
                                    if (context != null) {
                                        performProfile(
                                            module,
                                            context,
                                            config.dismissProfile,
                                            clearAllHaptic,
                                            appFeatureUtils
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
                        "dismiss haptic hook failed ${method.name}: ${it.message}"
                    )
                }
            }

        // Replace only the exact ColorOS Clear-All motor call when a non-OEM profile is chosen.
        if (clearAllHaptic != null) {
            runCatching {
                module.hook(clearAllHaptic)
                    .setId("launcher17312/haptic/clearAllProfile")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!config.hapticEffects || bypassClearAllOverride.get() == true) {
                            return@intercept chain.proceed()
                        }
                        val wave = (chain.args.getOrNull(1) as? Number)?.toInt()
                        val delay = (chain.args.getOrNull(2) as? Number)?.toLong()
                        val strength = (chain.args.getOrNull(3) as? Number)?.toInt()
                        val isClearAllSignature =
                            (wave == 472 || wave == 50) && delay == 0L && strength == 12
                        val profile = config.clearAllProfile
                        if (!isClearAllSignature || profile == LauncherHapticProfile.OEM_CLEAR_ALL) {
                            return@intercept chain.proceed()
                        }
                        val context = chain.args.firstOrNull() as? Context
                        if (context != null) profile.vibrate(context)
                        null
                    }
                count++
            }.onFailure {
                module.log(Log.WARN, TAG, "clear-all profile hook failed: ${it.message}")
            }
        }

        // Entering overview/recents: one pulse on setOverviewStateEnabled(true).
        cls.declaredMethods
            .filter {
                it.name == "setOverviewStateEnabled" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(Boolean::class.javaPrimitiveType)
                    )
            }
            .forEachIndexed { index, method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method)
                        .setId("launcher17312/haptic/recentsEnter/$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val enabled = (chain.args.firstOrNull() as? Boolean) == true
                            if (config.hapticEffects && enabled) {
                                val view = chain.thisObject as? View
                                val now = SystemClock.elapsedRealtime()
                                if (view?.isAttachedToWindow == true &&
                                    now - lastRecentsEnterHapticAt >= 250L) {
                                    lastRecentsEnterHapticAt = now
                                    performProfile(
                                        module,
                                        view.context,
                                        config.recentsEnterProfile,
                                        clearAllHaptic,
                                        appFeatureUtils
                                    )
                                }
                            }
                            chain.proceed()
                        }
                    count++
                }.onFailure {
                    module.log(Log.WARN, TAG, "recents-enter hook failed: ${it.message}")
                }
            }

        module.log(Log.INFO, TAG, "haptic hooks installed x$count config=$config")
        return count
    }

    private fun performProfile(
        module: Main,
        context: Context,
        profile: LauncherHapticProfile,
        clearAllHaptic: java.lang.reflect.Method?,
        appFeatureUtils: Pair<Any, java.lang.reflect.Method>?
    ) {
        if (profile == LauncherHapticProfile.NONE) return
        if (profile != LauncherHapticProfile.OEM_CLEAR_ALL) {
            profile.vibrate(context)
            return
        }
        if (clearAllHaptic == null) return
        runCatching {
            val supportKillWave = appFeatureUtils?.let { (instance, method) ->
                (method.invoke(instance) as? Boolean) == true
            } ?: false
            val waveId = if (supportKillWave) 472 else 50
            bypassClearAllOverride.set(true)
            try {
                clearAllHaptic.invoke(null, context, waveId, 0L, 12)
            } finally {
                bypassClearAllOverride.remove()
            }
        }.onFailure {
            module.log(Log.WARN, TAG, "OEM haptic failed: ${it.message}")
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
    private fun applyBubbleLabel(host: Any) {
        val textView = host as? TextView ?: return
        val inDrawer = (invokeNoArgDeep(host, "isInDrawer") as? Boolean) == true
        val hide = if (inDrawer) config.hideDrawerLabels else config.hideWorkspaceLabels
        setTextHiddenPreservingPaint(textView, hide)
    }

    private fun applyPageIndicator(host: Any) {
        val view = host as? View ?: return
        setHiddenPreservingState(view, config.hidePageIndicator)
    }

    private fun applyBottomSearch(host: Any) {
        val view = host as? View ?: return
        setHiddenPreservingState(view, config.hideBottomSearch)
    }

    private fun setTextHiddenPreservingPaint(view: TextView, hide: Boolean) {
        synchronized(originalTextPaintAlpha) {
            if (hide) {
                if (!originalTextPaintAlpha.containsKey(view)) {
                    originalTextPaintAlpha[view] = view.paint.alpha
                }
                if (view.paint.alpha != 0) {
                    view.paint.alpha = 0
                    view.invalidate()
                }
            } else {
                val original = originalTextPaintAlpha.remove(view)
                if (original != null && view.paint.alpha != original) {
                    view.paint.alpha = original
                    view.invalidate()
                }
            }
        }
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

    private fun invokeNoArgDeep(host: Any, name: String): Any? {
        var cls: Class<*>? = host.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(host)
                }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

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
            refreshList(bubbleLabels) { applyBubbleLabel(it) }
            refreshList(pageIndicators) { applyPageIndicator(it) }
            refreshList(bottomSearchViews) { applyBottomSearch(it) }
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

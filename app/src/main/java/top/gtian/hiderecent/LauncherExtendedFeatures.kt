package top.gtian.hiderecent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Extended Launcher feature layer.
 *
 * Sources used as behavioural references:
 * - LuckyTool launcher hooks (GPL-3.0)
 * - OShin launcher hooks (AGPL-3.0)
 *
 * This implementation is original and targets the verified ColorOS Launcher 17.3.12
 * signatures. Every feature is runtime-gated and defaults to disabled.
 */
internal object LauncherExtendedFeatures {
    private const val TAG = "${Main.TAG}/extended"

    private data class Config(
        val forceMemoryInfo: Boolean = false,
        val autoCloseFolder: Boolean = false,
        val removeFolderPreviewBg: Boolean = false,
        val removeUpdateGreenDot: Boolean = false,
        val hideRecentsDock: Boolean = false,
        val restorePinCapsule: Boolean = false,
        val recentsLongPressAppInfo: Boolean = false,
        val disableAutoFocusNextTask: Boolean = false,
        val enableIndicatorEntry: Boolean = false,
        val enableDockBackground: Boolean = false,
        val forceDockBlur: Boolean = false,
        val unlimitFolderName: Boolean = false,
        val disableIconSecondaryMenu: Boolean = false,
        val allowExcludedTaskLock: Boolean = false,
        val unlockTaskLockLimit: Boolean = false,
        val removeShortcutBadge: Boolean = false,
        val removeWorkBadge: Boolean = false,
        val removeCloneBadge: Boolean = false,

        val dockAlphaEnabled: Boolean = false,
        val dockAlpha: Float = 1f,
        val blurCornerEnabled: Boolean = false,
        val blurCornerDp: Float = 28f,
        val dockMaxEnabled: Boolean = false,
        val dockMaxItems: Int = 8,
        val defaultHomeEnabled: Boolean = false,
        val defaultHomePage: Int = 0,
        val folderGridEnabled: Boolean = false,
        val folderRows: Int = 4,
        val folderColumns: Int = 3,
        val drawerGridEnabled: Boolean = false,
        val drawerColumns: Int = 4,
        val forceFoldMode: Boolean = false,
        val foldMode: Int = 0,
        val customIconSizeEnabled: Boolean = false,
        val iconSizeDp: Int = 56
    )

    @Volatile private var config = Config()
    @Volatile private var module: Main? = null
    @Volatile private var loader: ClassLoader? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private data class LongClickWrap(
        val original: View.OnLongClickListener?,
        val wrapper: View.OnLongClickListener
    )

    private val recentsLongClickWraps = Collections.synchronizedMap(
        WeakHashMap<View, LongClickWrap>()
    )

    fun refresh(prefs: SharedPreferences) {
        config = Config(
            forceMemoryInfo = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FORCE_MEMORY_INFO, false
            ),
            autoCloseFolder = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_AUTO_CLOSE_FOLDER, false
            ),
            removeFolderPreviewBg = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_REMOVE_FOLDER_PREVIEW_BG, false
            ),
            removeUpdateGreenDot = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_REMOVE_UPDATE_GREEN_DOT, false
            ),
            hideRecentsDock = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_HIDE_RECENTS_DOCK, false
            ),
            restorePinCapsule = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_RESTORE_PIN_CAPSULE, false
            ),
            recentsLongPressAppInfo = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_RECENTS_LONG_PRESS_APP_INFO, false
            ),
            disableAutoFocusNextTask = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_DISABLE_AUTO_FOCUS_NEXT_TASK, false
            ),
            enableIndicatorEntry = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ENABLE_INDICATOR_ENTRY, false
            ),
            enableDockBackground = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ENABLE_DOCK_BACKGROUND, false
            ),
            forceDockBlur = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FORCE_DOCK_BLUR, false
            ),
            unlimitFolderName = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_UNLIMIT_FOLDER_NAME, false
            ),
            disableIconSecondaryMenu = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_DISABLE_ICON_SECONDARY_MENU, false
            ),
            allowExcludedTaskLock = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_ALLOW_EXCLUDED_TASK_LOCK, false
            ),
            unlockTaskLockLimit = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_UNLOCK_TASK_LOCK_LIMIT, false
            ),
            removeShortcutBadge = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_REMOVE_SHORTCUT_BADGE, false
            ),
            removeWorkBadge = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_REMOVE_WORK_BADGE, false
            ),
            removeCloneBadge = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_REMOVE_CLONE_BADGE, false
            ),

            dockAlphaEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_DOCK_ALPHA_ENABLED, false
            ),
            dockAlpha = prefs.getFloat(
                LauncherStabilityPrefs.KEY_DOCK_ALPHA, 1f
            ).coerceIn(0f, 1f),
            blurCornerEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_BLUR_CORNER_ENABLED, false
            ),
            blurCornerDp = prefs.getFloat(
                LauncherStabilityPrefs.KEY_BLUR_CORNER_DP, 28f
            ).coerceIn(0f, 100f),
            dockMaxEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_DOCK_MAX_ENABLED, false
            ),
            dockMaxItems = prefs.getInt(
                LauncherStabilityPrefs.KEY_DOCK_MAX_ITEMS, 8
            ).coerceIn(5, 20),
            defaultHomeEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_DEFAULT_HOME_ENABLED, false
            ),
            defaultHomePage = prefs.getInt(
                LauncherStabilityPrefs.KEY_DEFAULT_HOME_PAGE, 0
            ).coerceIn(0, 19),
            folderGridEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FOLDER_GRID_ENABLED, false
            ),
            folderRows = prefs.getInt(
                LauncherStabilityPrefs.KEY_FOLDER_ROWS, 4
            ).coerceIn(2, 8),
            folderColumns = prefs.getInt(
                LauncherStabilityPrefs.KEY_FOLDER_COLUMNS, 3
            ).coerceIn(2, 8),
            drawerGridEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_DRAWER_GRID_ENABLED, false
            ),
            drawerColumns = prefs.getInt(
                LauncherStabilityPrefs.KEY_DRAWER_COLUMNS, 4
            ).coerceIn(3, 10),
            forceFoldMode = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_FORCE_FOLD_MODE, false
            ),
            foldMode = prefs.getInt(
                LauncherStabilityPrefs.KEY_FOLD_MODE, 0
            ).coerceIn(0, 1),
            customIconSizeEnabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_CUSTOM_ICON_SIZE_ENABLED, false
            ),
            iconSizeDp = prefs.getInt(
                LauncherStabilityPrefs.KEY_ICON_SIZE_DP, 56
            ).coerceIn(36, 96)
        )

        applyLiveLayoutProfile()
    }

    fun reset() {
        config = Config()
        module = null
        loader = null
        recentsLongClickWraps.clear()
    }

    fun hook(module: Main, loader: ClassLoader): Int {
        this.module = module
        this.loader = loader

        var count = 0
        count += hookMemoryInfo(module, loader)
        count += hookAutoCloseFolder(module, loader)
        count += hookFolderPreviewBackground(module, loader)
        count += hookUpdateGreenDot(module, loader)
        count += hookRecentsDock(module, loader)
        count += hookPinCapsuleGate(module, loader)
        count += hookRecentsAppInfo(module, loader)
        count += hookAutoFocus(module, loader)
        count += hookIndicatorEntry(module, loader)
        count += hookDockFeatures(module, loader)
        count += hookFolderNameLimit(module, loader)
        count += hookSecondaryMenu(module, loader)
        count += hookTaskLock(module, loader)
        count += hookIconAppearance(module, loader)
        count += hookDefaultHome(module, loader)
        count += hookLayoutProfile(module, loader)

        module.log(Log.INFO, TAG, "extended hooks installed=$count")
        applyLiveLayoutProfile()
        return count
    }

    private fun hookMemoryInfo(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.oplus.quickstep.memory.MemoryInfoManager")
            ?: return 0
        var count = 0
        cls.declaredMethods
            .filter {
                it.name in setOf("isAllowMemoryInfoDisplay", "needMemoryDetail") &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "memory/${method.name}/$index") { chain ->
                    if (config.forceMemoryInfo) true else chain.proceed()
                }
            }
        return count
    }

    private fun hookAutoCloseFolder(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.launcher3.AbstractFloatingView")
            ?: return 0
        val folderTypeField = findField(cls, "TYPE_FOLDER")
        var count = 0

        cls.declaredMethods
            .filter {
                it.name == "closeOpenViews" &&
                    it.parameterTypes.size in 3..4
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "folder/autoClose/$index") { chain ->
                    if (config.autoCloseFolder) {
                        runCatching {
                            val activityContext = chain.args.getOrNull(0) ?: return@runCatching
                            val animate = chain.args.getOrNull(1) as? Boolean ?: true
                            val type = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
                            val folderType = folderTypeField?.getInt(null) ?: 0
                            if (folderType != 0 && type and folderType != 0) {
                                val dragLayer = invokeNoArgDeep(activityContext, "getDragLayer")
                                    as? ViewGroup
                                if (dragLayer != null) {
                                    for (i in 0 until dragLayer.childCount) {
                                        val child = dragLayer.getChildAt(i)
                                        if (!cls.isAssignableFrom(child.javaClass)) continue
                                        val isFolder = invokeMethodDeep(
                                            child,
                                            "isOfType",
                                            arrayOf<Class<*>?>(Int::class.javaPrimitiveType),
                                            arrayOf<Any?>(folderType)
                                        ) as? Boolean ?: false
                                        if (isFolder) {
                                            invokeMethodDeep(
                                                child,
                                                "close",
                                                arrayOf<Class<*>?>(Boolean::class.javaPrimitiveType),
                                                arrayOf<Any?>(animate)
                                            )
                                        }
                                    }
                                }
                            }
                        }.onFailure {
                            module.log(Log.WARN, TAG, "auto-close folder failed: ${it.message}")
                        }
                    }
                    chain.proceed()
                }
            }
        return count
    }

    private fun hookFolderPreviewBackground(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.launcher3.folder.OplusPreviewBackground")
            ?: return 0
        var count = 0

        cls.declaredMethods
            .filter { it.name == "drawBackground" }
            .forEachIndexed { index, method ->
                count += hook(module, method, "folder/bg/draw/$index") { chain ->
                    if (config.removeFolderPreviewBg) null else chain.proceed()
                }
            }

        cls.declaredMethods
            .filter { it.name == "setBackground" }
            .forEachIndexed { index, method ->
                count += hook(module, method, "folder/bg/set/$index") { chain ->
                    if (config.removeFolderPreviewBg) {
                        findField(chain.thisObject?.javaClass, "mBgDrawable")
                            ?.set(chain.thisObject, null)
                    }
                    chain.proceed()
                }
            }

        return count
    }

    private fun hookUpdateGreenDot(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.launcher3.BubbleTextView") ?: return 0
        var count = 0
        cls.declaredMethods
            .filter { it.name == "isShouldShowGreenDot" }
            .forEachIndexed { index, method ->
                count += hook(module, method, "badge/greenDot/$index") { chain ->
                    if (config.removeUpdateGreenDot) false else chain.proceed()
                }
            }
        return count
    }

    private fun hookRecentsDock(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.oplus.quickstep.dock.DockView") ?: return 0
        var count = 0
        cls.declaredMethods
            .filter { it.name == "hideDockView" && it.parameterTypes.size == 1 }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/dockHide/$index") { chain ->
                    if (config.hideRecentsDock &&
                        method.parameterTypes[0] == Boolean::class.javaPrimitiveType
                    ) {
                        val args = chain.args.toTypedArray()
                        args[0] = true
                        chain.proceed(args)
                    } else {
                        chain.proceed()
                    }
                }
            }
        return count
    }

    private fun hookRecentsAppInfo(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.quickstep.views.OplusTaskViewImpl")
            ?: return 0
        var count = 0

        cls.declaredMethods
            .filter { it.name == "setIcon" && it.parameterTypes.size in 1..2 }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/appInfo/$index") { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val host = chain.thisObject ?: return@runCatching
                        val task = invokeNoArgDeep(host, "getTask") ?: return@runCatching
                        val key = findField(task.javaClass, "key")?.get(task)
                            ?: return@runCatching
                        val packageName = invokeNoArgDeep(key, "getPackageName") as? String
                            ?: return@runCatching
                        val userId = (findField(key.javaClass, "userId")?.get(key) as? Number)
                            ?.toInt()

                        val header = invokeNoArgDeep(host, "getHeaderView")
                            ?: return@runCatching
                        val icon = invokeNoArgDeep(header, "getTaskIcon") as? View
                        val title =
                            (invokeNoArgDeep(header, "getTitleTv") as? View)
                                ?: (findField(header.javaClass, "titleTv")?.get(header) as? View)

                        if (icon != null) wrapAppInfoLongClick(icon, packageName, userId)
                        if (title != null) wrapAppInfoLongClick(title, packageName, userId)
                    }.onFailure {
                        module.log(Log.WARN, TAG, "recents app-info binding failed: ${it.message}")
                    }
                    result
                }
            }

        return count
    }

    private fun wrapAppInfoLongClick(
        view: View,
        packageName: String,
        userId: Int?
    ) {
        synchronized(recentsLongClickWraps) {
            val current = readLongClickListener(view)
            val existing = recentsLongClickWraps[view]
            if (existing != null && current === existing.wrapper) return

            val original = current
            val wrapper = View.OnLongClickListener { clicked ->
                if (!config.recentsLongPressAppInfo) {
                    return@OnLongClickListener original?.onLongClick(clicked) ?: false
                }

                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val opened = runCatching {
                    if (userId != null) {
                        val method = Context::class.java.getMethod(
                            "startActivityAsUser",
                            Intent::class.java,
                            UserHandle::class.java
                        )
                        val userHandle = UserHandle::class.java
                            .getMethod("of", Int::class.javaPrimitiveType)
                            .invoke(null, userId)
                        method.invoke(clicked.context, intent, userHandle)
                    } else {
                        clicked.context.startActivity(intent)
                    }
                    true
                }.getOrElse {
                    runCatching {
                        clicked.context.startActivity(intent)
                        true
                    }.getOrDefault(false)
                }

                opened || (original?.onLongClick(clicked) ?: false)
            }

            view.setOnLongClickListener(wrapper)
            recentsLongClickWraps[view] = LongClickWrap(original, wrapper)
        }
    }

    private fun readLongClickListener(view: View): View.OnLongClickListener? =
        runCatching {
            val getListenerInfo = View::class.java.getDeclaredMethod("getListenerInfo")
            getListenerInfo.isAccessible = true
            val listenerInfo = getListenerInfo.invoke(view) ?: return@runCatching null
            val field = listenerInfo.javaClass.getDeclaredField("mOnLongClickListener")
            field.isAccessible = true
            field.get(listenerInfo) as? View.OnLongClickListener
        }.getOrNull()

    /**
     * Restore the OEM ColorOS 17 "Pin to Capsule / Fluid Cloud" recent-task shortcut.
     *
     * OplusTaskShortcutsFactory first checks AppFeatureUtils.isSupportPinCapsule().
     * We only restore that global feature gate. The stock CapsuleManager.isTaskSupportPin()
     * still decides per task/app support, and stock pinToCapsule()/unpinCapsule() continue
     * to call the SystemUI Seedling provider.
     */
    private fun hookPinCapsuleGate(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.common.util.AppFeatureUtils")
            ?: return 0

        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "isSupportPinCapsule" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "capsule/supportGate/$index"
                ) { chain ->
                    if (config.restorePinCapsule) true else chain.proceed()
                }
            }

        return count
    }

    private fun hookAutoFocus(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.common.util.AppFeatureUtils") ?: return 0
        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "isSupportAutoFocusToNextPageInOverviewState" &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "recents/autoFocus/$index") { chain ->
                    if (config.disableAutoFocusNextTask) false else chain.proceed()
                }
            }
        return count
    }

    private fun hookIndicatorEntry(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(
            loader,
            "com.android.launcher3.search.IndicatorEntry\$Companion"
        ) ?: return 0
        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "isSupportIndicatorEntryMenu" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "indicator/menu/$index") { chain ->
                    if (config.enableIndicatorEntry) true else chain.proceed()
                }
            }
        return count
    }

    private fun hookDockFeatures(module: Main, loader: ClassLoader): Int {
        var count = 0

        loadClass(loader, "com.android.common.util.ScreenUtils")?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name in setOf(
                        "isSupportDockerExpandScreen",
                        "isFoldScreenExpanded",
                        "isFoldScreenFolded",
                        "hasLargeDisplayFeatures"
                    ) && it.returnType == Boolean::class.javaPrimitiveType
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "screen/${method.name}/$index") { chain ->
                        when (method.name) {
                            "isSupportDockerExpandScreen" ->
                                if (config.enableDockBackground || config.forceFoldMode) {
                                    true
                                } else chain.proceed()
                            "hasLargeDisplayFeatures" ->
                                if (config.forceDockBlur) true else chain.proceed()
                            "isFoldScreenExpanded" ->
                                if (config.forceFoldMode) config.foldMode == 0 else chain.proceed()
                            "isFoldScreenFolded" ->
                                if (config.forceFoldMode) config.foldMode == 1 else chain.proceed()
                            else -> chain.proceed()
                        }
                    }
                }
        }

        loadClass(loader, "com.android.launcher3.OplusHotseat")?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "setBackgroundAlpha" &&
                        it.parameterTypes.contentEquals(
                            arrayOf(Float::class.javaPrimitiveType)
                        )
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "dock/alpha/$index") { chain ->
                        if (config.dockAlphaEnabled) {
                            val args = chain.args.toTypedArray()
                            args[0] = config.dockAlpha
                            chain.proceed(args)
                        } else {
                            chain.proceed()
                        }
                    }
                }
        }

        loadClass(
            loader,
            "com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties"
        )?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "isSupportNewBlur" &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "dock/newBlur/$index") { chain ->
                        if (config.forceDockBlur) true else chain.proceed()
                    }
                }

            cls.declaredMethods
                .filter {
                    it.name == "setBlurCornerRadius" &&
                        it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0] == Float::class.javaPrimitiveType
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "blur/corner/$index") { chain ->
                        if (config.blurCornerEnabled) {
                            val density = module.currentContext()
                                ?.resources
                                ?.displayMetrics
                                ?.density ?: 1f
                            val args = chain.args.toTypedArray()
                            args[0] = config.blurCornerDp * density
                            chain.proceed(args)
                        } else {
                            chain.proceed()
                        }
                    }
                }
        }

        loadClass(loader, "com.android.launcher3.hotseat.expand.ExpandConfig")?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "getHotseatNormalItemsMaxCountBy" &&
                        it.returnType == Int::class.javaPrimitiveType
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "dock/maxItems/$index") { chain ->
                        val stock = (chain.proceed() as? Number)?.toInt() ?: 0
                        if (config.dockMaxEnabled) {
                            max(stock, config.dockMaxItems)
                        } else stock
                    }
                }
        }

        return count
    }

    private fun hookFolderNameLimit(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.launcher3.folder.OplusFolder") ?: return 0
        var count = 0
        cls.declaredMethods
            .filter { it.name == "onFinishInflate" && it.parameterTypes.isEmpty() }
            .forEachIndexed { index, method ->
                count += hook(module, method, "folder/nameLimit/$index") { chain ->
                    val result = chain.proceed()
                    if (config.unlimitFolderName) {
                        val host = chain.thisObject
                        val field = findField(host?.javaClass, "mTextWatcher")
                        if (host != null && field != null) {
                            val noOp = object : android.text.TextWatcher {
                                override fun beforeTextChanged(
                                    s: CharSequence?, start: Int, count: Int, after: Int
                                ) = Unit
                                override fun onTextChanged(
                                    s: CharSequence?, start: Int, before: Int, count: Int
                                ) = Unit
                                override fun afterTextChanged(s: android.text.Editable?) = Unit
                            }
                            field.set(host, noOp)
                        }
                    }
                    result
                }
            }
        return count
    }

    private fun hookSecondaryMenu(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.launcher3.popup.PopupDataProvider")
            ?: return 0
        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "getNotificationKeysForItem" &&
                    it.parameterTypes.size == 1
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "popup/secondary/$index") { chain ->
                    if (!config.disableIconSecondaryMenu) return@hook chain.proceed()

                    val item = chain.args.firstOrNull()
                        ?: return@hook chain.proceed()
                    val field = findField(item.javaClass, "mAddShortcutCount")
                        ?: return@hook chain.proceed()
                    val old = runCatching { field.get(item) }.getOrNull()
                    try {
                        field.set(item, 0)
                        chain.proceed()
                    } finally {
                        runCatching { field.set(item, old) }
                    }
                }
            }
        return count
    }

    private fun hookTaskLock(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.oplus.quickstep.applock.OplusLockManager")
            ?: return 0
        var count = 0

        cls.declaredMethods
            .filter {
                it.name == "isAppSupportLock" &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "taskLock/excluded/$index") { chain ->
                    if (!config.allowExcludedTaskLock) return@hook chain.proceed()
                    val intent = chain.args.lastOrNull { it is Intent } as? Intent
                        ?: return@hook chain.proceed()
                    val excluded =
                        intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0
                    if (!excluded) return@hook chain.proceed()

                    intent.removeFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    try {
                        chain.proceed()
                    } finally {
                        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    }
                }
            }

        cls.declaredMethods
            .filter {
                it.name in setOf("isAppAllowLock", "isAppAllowUnlock", "canLockApp") &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(module, method, "taskLock/limit/${method.name}/$index") { chain ->
                    if (config.unlockTaskLockLimit) true else chain.proceed()
                }
            }

        return count
    }

    private fun hookIconAppearance(module: Main, loader: ClassLoader): Int {
        var count = 0

        loadClass(loader, "com.android.launcher3.BubbleTextView")?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "getIconSize" &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == Int::class.javaPrimitiveType
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "icon/size/$index") { chain ->
                        if (!config.customIconSizeEnabled) return@hook chain.proceed()
                        val density = module.currentContext()
                            ?.resources
                            ?.displayMetrics
                            ?.density ?: 1f
                        (config.iconSizeDp * density).toInt()
                    }
                }
        }

        loadClass(loader, "com.android.launcher3.icons.BitmapInfo")?.let { cls ->
            cls.declaredMethods
                .filter {
                    it.name == "applyFlags" &&
                        it.returnType == Void.TYPE
                }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "icon/badge/$index") { chain ->
                        if (!config.removeShortcutBadge &&
                            !config.removeWorkBadge &&
                            !config.removeCloneBadge
                        ) {
                            return@hook chain.proceed()
                        }

                        val host = chain.thisObject ?: return@hook chain.proceed()
                        val flagsField = findField(host.javaClass, "flags")
                        val badgeField = findField(host.javaClass, "badgeInfo")
                        val oldFlags = runCatching { flagsField?.getInt(host) }.getOrNull()
                        val oldBadge = runCatching { badgeField?.get(host) }.getOrNull()

                        try {
                            if (oldFlags != null) {
                                var next = oldFlags
                                if (config.removeWorkBadge) next = next and 1.inv()
                                if (config.removeCloneBadge) next = next and 4.inv()
                                flagsField?.setInt(host, next)
                            }
                            if (config.removeShortcutBadge && badgeField != null) {
                                badgeField.set(host, null)
                            }
                            chain.proceed()
                        } finally {
                            if (oldFlags != null) runCatching { flagsField?.setInt(host, oldFlags) }
                            if (badgeField != null) runCatching { badgeField.set(host, oldBadge) }
                        }
                    }
                }
        }

        return count
    }

    private fun hookDefaultHome(module: Main, loader: ClassLoader): Int {
        val cls = loadClass(loader, "com.android.launcher3.Workspace") ?: return 0
        val defaultPage = findField(cls, "DEFAULT_PAGE")
        var count = 0

        cls.declaredMethods
            .filter { it.name == "moveToDefaultScreen" && it.parameterTypes.isEmpty() }
            .forEachIndexed { index, method ->
                count += hook(module, method, "workspace/defaultPage/$index") { chain ->
                    if (config.defaultHomeEnabled) {
                        runCatching { defaultPage?.setInt(null, config.defaultHomePage) }
                    }
                    chain.proceed()
                }
            }

        return count
    }

    private fun hookLayoutProfile(module: Main, loader: ClassLoader): Int {
        var count = 0

        loadClass(loader, "com.android.launcher3.InvariantDeviceProfile")?.let { cls ->
            cls.declaredMethods
                .filter { it.name == "initGrid" }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "layout/idp/$index") { chain ->
                        val result = chain.proceed()
                        applyIdpFields(chain.thisObject)
                        result
                    }
                }
        }

        loadClass(loader, "com.android.launcher3.model.data.FolderInfo")?.let { cls ->
            cls.declaredMethods
                .filter { it.name in setOf("getPreviewRow", "getPreviewColumn") }
                .forEachIndexed { index, method ->
                    count += hook(module, method, "layout/folderPreview/${method.name}/$index") { chain ->
                        if (!config.folderGridEnabled) return@hook chain.proceed()
                        if (method.name == "getPreviewRow") {
                            config.folderRows
                        } else {
                            config.folderColumns
                        }
                    }
                }
        }

        return count
    }

    private fun applyLiveLayoutProfile() {
        val currentLoader = loader ?: return
        val currentModule = module ?: return
        if (!config.folderGridEnabled && !config.drawerGridEnabled) return

        mainHandler.post {
            runCatching {
                val appStateCls = currentLoader.loadClass("com.android.launcher3.LauncherAppState")
                val noCreate = appStateCls.declaredMethods.firstOrNull {
                    it.name == "getInstanceNoCreate" && it.parameterTypes.isEmpty()
                } ?: return@runCatching
                noCreate.isAccessible = true
                val state = noCreate.invoke(null) ?: return@runCatching

                val idp = invokeNoArgDeep(state, "getInvariantDeviceProfile")
                    ?: return@runCatching
                applyIdpFields(idp)
            }.onFailure {
                currentModule.log(Log.WARN, TAG, "live layout patch skipped: ${it.message}")
            }
        }
    }

    private fun applyIdpFields(idp: Any?) {
        if (idp == null) return
        if (config.folderGridEnabled) {
            writeIntField(idp, "numFolderRows", config.folderRows)
            writeIntField(idp, "numFolderColumns", config.folderColumns)
        }
        if (config.drawerGridEnabled) {
            writeIntField(idp, "numAllAppsColumns", config.drawerColumns)
            writeIntField(idp, "numDatabaseAllAppsColumns", config.drawerColumns)
        }
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
                .setId("launcher17312/ext/$id")
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

    private fun findField(cls: Class<*>?, name: String): Field? {
        var current = cls
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun writeIntField(host: Any, name: String, value: Int) {
        runCatching {
            val field = findField(host.javaClass, name) ?: return
            field.setInt(host, value)
        }
    }

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

    private fun invokeMethodDeep(
        host: Any,
        name: String,
        parameterTypes: Array<Class<*>?>,
        args: Array<Any?>
    ): Any? {
        var cls: Class<*>? = host.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == parameterTypes.size &&
                    it.parameterTypes.indices.all { i ->
                        parameterTypes[i] == null || it.parameterTypes[i] == parameterTypes[i]
                    }
            }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(host, *args)
                }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }
}

package top.gtian.hiderecent

import android.app.ActivityManager
import android.app.Notification
import android.app.TaskInfo
import android.content.Context
import android.content.ContentProvider
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ColorOS 17 PinTask -> Fluid Cloud compatibility bridge.
 *
 * Launcher 17.3.12 still calls:
 *   content://com.oplus.card.server.systemui.provider/
 *   method = oplus_pin_task / oplus_cancel_pin
 *
 * The current SystemUI/SystemUIPlugin keeps the LiveAlert + PinTaskInterceptor data model,
 * but no longer exposes those legacy provider methods. This bridge translates only these
 * two legacy calls into the still-supported sendLiveAlert / cancelLiveAlert protocol.
 *
 * Safety:
 * - SystemUI-only.
 * - default-off until RemotePreferences arrive.
 * - exact method-name interception only.
 * - no system_server injection.
 * - no WindowManager / SurfaceControl mutation.
 * - all non-PinTask provider traffic passes through untouched.
 */
internal object FluidCloudCompatBridge {
    private const val TAG = "${Main.TAG}/fluidCompat"
    private const val PREF_RETRY_MS = 1_500L
    private const val PREF_MAX_ATTEMPTS = 40

    private const val FEATURE_PIN_TASK =
        "oplus.software.systemui.pin_task"
    private const val METHOD_PIN = "oplus_pin_task"
    private const val METHOD_UNPIN = "oplus_cancel_pin"
    private const val METHOD_SEND = "sendLiveAlert"
    private const val METHOD_CANCEL = "cancelLiveAlert"
    private const val KEY_CONTENT = "content"
    private const val KEY_PIN_INFO = "oplus.pinTaskInfo"
    private const val KEY_LIVE_ALERT_OPTIONS = "oplusLiveAlertOptions"
    private const val KEY_RESULT = "resultCode"

    // Exact legacy PinTask identity from SystemUIPlugin 16.000.002.
    private const val PIN_NOTIFICATION_CHANNEL = "oplus_pin_task"
    private const val PIN_NOTIFICATION_ID = 20_000
    private const val OPLUS_PIN_INTENT_FLAG = 0x800

    private data class Config(
        val enabled: Boolean = false
    )

    private data class PinRecord(
        val taskId: Int,
        val packageName: String,
        val sbn: StatusBarNotification
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val bootstrapStarted = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val pinsByTaskId = ConcurrentHashMap<Int, PinRecord>()
    private val dynamicProviderHooks = ConcurrentHashMap.newKeySet<String>()

    fun hook(module: Main, loader: ClassLoader) {
        if (!bootstrapStarted.compareAndSet(false, true)) return
        val expectedGeneration = generation.incrementAndGet()

        var count = 0
        count += hookFeatureGate(module, loader)
        count += hookSeedlingProvider(module, loader)

        module.log(
            if (count > 0) Log.INFO else Log.WARN,
            TAG,
            "SystemUI fluid-cloud compat skeleton installed hooks=$count"
        )

        Thread({
            bootstrapPrefs(module, expectedGeneration)
        }, "LauncherStabilityFluidPrefs").apply {
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
        pinsByTaskId.clear()
        dynamicProviderHooks.clear()
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
                    "remote prefs attached attempt=$attempt enabled=${config.enabled}"
                )
                PinTaskRuntimeTraceReporter.record(
                    context = module.currentContext(),
                    stage = PinTaskRuntimeTrace.STAGE_SYSTEMUI_READY,
                    detail = "SystemUI hook ready; wrapperHooks active; enabled=${config.enabled}"
                )
                return
            }

            SystemClock.sleep(PREF_RETRY_MS)
        }

        module.log(
            Log.WARN,
            TAG,
            "remote prefs unavailable; compatibility bridge stays pass-through"
        )
    }

    private fun refresh(prefs: SharedPreferences) {
        config = Config(
            enabled = prefs.getBoolean(
                LauncherStabilityPrefs.KEY_RESTORE_PIN_CAPSULE,
                false
            )
        )

        if (!config.enabled) {
            pinsByTaskId.clear()
        }
    }

    /**
     * Match LuckyTool's feature-gate level instead of only overriding a Launcher helper.
     * This makes SystemUIPlugin's own isPinTaskEnabled gate observe the same state.
     */
    private fun hookFeatureGate(
        module: Main,
        loader: ClassLoader
    ): Int {
        val cls = loadClass(
            loader,
            "com.oplus.content.OplusFeatureConfigManager"
        ) ?: return 0

        var count = 0
        cls.declaredMethods
            .filter {
                it.name == "hasFeature" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(String::class.java)
                    ) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "feature/pinTask/$index"
                ) { chain ->
                    val key = chain.args.firstOrNull() as? String
                    if (config.enabled && key == FEATURE_PIN_TASK) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            }

        return count
    }

    private fun hookSeedlingProvider(
        module: Main,
        loader: ClassLoader
    ): Int {
        val cls = loadClass(
            loader,
            "com.oplus.systemui.statusbar.seeding.SeedlingCardProvider"
        ) ?: return 0

        var count = 0

        cls.declaredMethods
            .filter {
                it.name == "call" &&
                    Bundle::class.java.isAssignableFrom(it.returnType)
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "provider/call/$index"
                ) { chain ->
                    if (!config.enabled) {
                        return@hook chain.proceed()
                    }

                    val methodIndex = chain.args.indexOfFirst {
                        it == METHOD_PIN || it == METHOD_UNPIN
                    }
                    if (methodIndex < 0) {
                        return@hook chain.proceed()
                    }

                    val legacyMethod =
                        chain.args[methodIndex] as? String
                            ?: return@hook chain.proceed()

                    PinTaskRuntimeTraceReporter.record(
                        context = module.currentContext(),
                        stage = PinTaskRuntimeTrace.STAGE_SYSTEMUI_INGRESS,
                        detail = "SeedlingCardProvider.call received $legacyMethod"
                    )

                    val extrasIndex = chain.args.indexOfLast {
                        it is Bundle
                    }
                    if (extrasIndex < 0) {
                        module.log(
                            Log.WARN,
                            TAG,
                            "legacy $legacyMethod received without Bundle; pass-through"
                        )
                        return@hook chain.proceed()
                    }

                    val legacyExtras =
                        chain.args[extrasIndex] as? Bundle
                            ?: return@hook chain.proceed()

                    val transformed = when (legacyMethod) {
                        METHOD_PIN -> buildPinTranslation(
                            module,
                            legacyExtras
                        )
                        METHOD_UNPIN -> buildUnpinTranslation(
                            module,
                            legacyExtras
                        )
                        else -> null
                    }

                    if (transformed == null) {
                        module.log(
                            Log.WARN,
                            TAG,
                            "cannot translate $legacyMethod; pass-through"
                        )
                        return@hook chain.proceed()
                    }

                    val args = chain.args.toTypedArray()
                    args[methodIndex] = transformed.first
                    args[extrasIndex] = transformed.second

                    module.log(
                        Log.INFO,
                        TAG,
                        "translate $legacyMethod -> ${transformed.first} " +
                            "taskId=${legacyExtras.getInt("taskId", -1)}"
                    )

                    // SeedlingCardServerProvider validates Binder.getCallingUid()
                    // against its allowed package list before dispatching sendLiveAlert.
                    // The legacy call originates from Launcher, but after translation we are
                    // forwarding inside SystemUI. Clear only this call's inbound identity so
                    // the downstream plugin sees the local SystemUI identity, then restore it.
                    val originalCallingUid = Binder.getCallingUid()
                    val identityToken = Binder.clearCallingIdentity()
                    val result = try {
                        chain.proceed(args) as? Bundle
                    } finally {
                        Binder.restoreCallingIdentity(identityToken)
                    }
                    val resultCode = result?.getInt(KEY_RESULT, 0) ?: 0

                    module.log(
                        Log.INFO,
                        TAG,
                        "translated callerUid=$originalCallingUid localUid=${android.os.Process.myUid()}"
                    )

                    module.log(
                        if (resultCode != 0) Log.INFO else Log.WARN,
                        TAG,
                        "translated $legacyMethod resultCode=$resultCode " +
                            "keys=${result?.keySet()?.sorted()}"
                    )

                    val taskId =
                        legacyExtras.getInt("taskId", -1)
                    val packageName =
                        legacyExtras.getString("packageName")

                    PinTaskRuntimeTraceReporter.record(
                        context = module.currentContext(),
                        stage = PinTaskRuntimeTrace.STAGE_SYSTEMUI,
                        taskId = taskId,
                        packageName = packageName,
                        detail = legacyMethod +
                            " -> " + transformed.first +
                            "; resultCode=" + resultCode +
                            "; success=" + (resultCode != 0)
                    )

                    if (legacyMethod == METHOD_PIN) {
                        if (resultCode == 0 && taskId >= 0) {
                            // buildPinTranslation creates the deterministic record before
                            // dispatch so cancel can reuse the exact SBN key. Drop it if the
                            // downstream send failed; never leave a false local pin state.
                            pinsByTaskId.remove(taskId)
                        }
                    } else if (
                        legacyMethod == METHOD_UNPIN &&
                        resultCode != 0 &&
                        taskId >= 0
                    ) {
                        pinsByTaskId.remove(taskId)
                    }

                    result
                }
            }

        cls.declaredMethods
            .filter {
                it.name in setOf(
                    "createPluginContentProvider",
                    "onPluginAdded",
                    "attachInfo"
                )
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "provider/pluginBootstrap/${method.name}/$index"
                ) { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { host ->
                        installDynamicPluginProviderHooks(
                            module = module,
                            host = host
                        )
                    }
                    result
                }
            }

        return count
    }

    private fun installDynamicPluginProviderHooks(
        module: Main,
        host: Any
    ) {
        val provider = findNestedContentProvider(host) ?: return
        val providerClass = provider.javaClass

        providerClass.declaredMethods
            .filter {
                it.name == "call" &&
                    Bundle::class.java.isAssignableFrom(it.returnType)
            }
            .forEachIndexed { index, method ->
                val key =
                    System.identityHashCode(providerClass.classLoader).toString() +
                        ":" + providerClass.name + ":" + method.toGenericString()
                if (!dynamicProviderHooks.add(key)) return@forEachIndexed

                val installed = hook(
                    module,
                    method,
                    "plugin/call/${providerClass.name}/$index"
                ) { chain ->
                    val calledMethod = chain.args.firstOrNull {
                        it is String && (
                            it == METHOD_SEND ||
                                it == METHOD_CANCEL ||
                                it == METHOD_PIN ||
                                it == METHOD_UNPIN
                            )
                    } as? String

                    if (calledMethod != null) {
                        PinTaskRuntimeTraceReporter.record(
                            context = module.currentContext(),
                            stage = PinTaskRuntimeTrace.STAGE_SYSTEMUI_PLUGIN,
                            detail =
                                providerClass.name +
                                    ".call(" + calledMethod + ")"
                        )
                    }

                    chain.proceed()
                }

                if (installed > 0) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "dynamic plugin provider hook installed " +
                            providerClass.name + " method=" + method.toGenericString()
                    )
                } else {
                    dynamicProviderHooks.remove(key)
                }
            }
    }

    private fun findNestedContentProvider(host: Any): ContentProvider? {
        var cls: Class<*>? = host.javaClass
        while (cls != null) {
            for (field in cls.declaredFields) {
                val candidate = runCatching {
                    field.isAccessible = true
                    field.get(host)
                }.getOrNull()

                if (candidate is ContentProvider &&
                    candidate.javaClass.name.contains(
                        "SeedlingCardServerProvider"
                    )
                ) {
                    return candidate
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun buildPinTranslation(
        module: Main,
        legacy: Bundle
    ): Pair<String, Bundle>? {
        val taskId = legacy.getInt("taskId", -1)
        if (taskId < 0) return null

        val context = module.currentContext()
            ?: return null
        val activityManager =
            context.getSystemService(ActivityManager::class.java)

        val taskInfo = activityManager?.let {
            findTaskInfo(it, taskId)
        }

        val rawBaseIntent = runCatching {
            @Suppress("DEPRECATION")
            legacy.getParcelable("baseIntent") as? Intent
        }.getOrNull() ?: taskInfo?.baseIntent

        val packageName =
            legacy.getString("packageName")
                ?.takeIf { it.isNotBlank() }
                ?: rawBaseIntent?.component?.packageName
                ?: taskInfo?.baseActivity?.packageName
                ?: taskInfo?.topActivity?.packageName
                ?: return null

        val userId = if (legacy.containsKey("userId")) {
            legacy.getInt("userId", 0)
        } else {
            taskInfo?.let(::readTaskUserId) ?: 0
        }

        val uid = legacy.getInt("uid", -1)
            .takeIf { it >= 0 }
            ?: resolvePackageUidForUser(
                context = context,
                packageName = packageName,
                userId = userId
            ).takeIf { it >= 0 }
            ?: return null

        val pid = legacy.getInt("pid", 0)
        val label = resolveAppLabel(context, packageName)

        val baseIntent = rawBaseIntent
            ?.let(::cloneWithPinTaskFlag)

        val launcherIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let(::cloneWithPinTaskFlag)

        // Keep the exact old oplus.pinTaskInfo payload shape. Do not leak the
        // outer provider's orientation/from fields into Notification extras.
        val pinInfo = Bundle().apply {
            putInt("taskId", taskId)
            putString("packageName", packageName)
            if (baseIntent != null) {
                putParcelable("baseIntent", baseIntent)
            }
            if (launcherIntent != null) {
                putParcelable("launcherIntent", launcherIntent)
            }
            putInt("userId", userId)
            putInt("uid", uid)
            putInt("pid", pid)
        }

        val notification = buildSyntheticNotification(
            context = context,
            packageName = packageName,
            title = label,
            pinInfo = pinInfo
        )

        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(
            packageName,
            packageName,
            PIN_NOTIFICATION_ID,
            taskId.toString(),
            uid,
            pid,
            0,
            notification,
            resolveUserHandle(userId),
            System.currentTimeMillis()
        )

        pinsByTaskId[taskId] = PinRecord(
            taskId = taskId,
            packageName = packageName,
            sbn = sbn
        )

        return METHOD_SEND to Bundle().apply {
            putParcelable(KEY_CONTENT, sbn)
        }
    }

    private fun cloneWithPinTaskFlag(source: Intent): Intent {
        val copy = Intent(source)
        runCatching {
            val method = Intent::class.java.getMethod(
                "addOplusFlags",
                Int::class.javaPrimitiveType
            )
            method.invoke(copy, OPLUS_PIN_INTENT_FLAG)
        }
        return copy
    }


    private fun resolveUserHandle(userId: Int): UserHandle =
        runCatching {
            val method = UserHandle::class.java.getMethod(
                "of",
                Int::class.javaPrimitiveType
            )
            method.invoke(null, userId) as UserHandle
        }.getOrElse {
            android.os.Process.myUserHandle()
        }

    private fun buildUnpinTranslation(
        module: Main,
        legacy: Bundle
    ): Pair<String, Bundle>? {
        val taskId = legacy.getInt("taskId", -1)
        if (taskId < 0) return null

        val record =
            pinsByTaskId[taskId]
                ?: reconstructPinRecord(
                    module = module,
                    taskId = taskId,
                    legacy = legacy
                )?.also {
                    pinsByTaskId[taskId] = it
                    module.log(
                        Log.INFO,
                        TAG,
                        "reconstructed PinTask cancel record after cache miss " +
                            "taskId=$taskId pkg=${it.packageName}"
                    )
                }

        if (record == null) {
            module.log(
                Log.WARN,
                TAG,
                "cancel requested but PinTask identity cannot be reconstructed " +
                    "for taskId=$taskId; pass-through"
            )
            return null
        }

        return METHOD_CANCEL to Bundle().apply {
            putParcelable(KEY_CONTENT, record.sbn)
        }
    }

    /**
     * SystemUI can restart independently from Launcher. The legacy unpin Bundle only carries
     * taskId/from/orientation, so the in-memory synthetic SBN cache may be gone even though the
     * task is still pinned. Recover the deterministic SBN identity from RunningTaskInfo.
     *
     * This is read-only recovery: no task/window state is changed here.
     */
    private fun reconstructPinRecord(
        module: Main,
        taskId: Int,
        legacy: Bundle
    ): PinRecord? {
        val context = module.currentContext() ?: return null
        val activityManager =
            context.getSystemService(ActivityManager::class.java)
                ?: return null

        val taskInfo = findTaskInfo(
            activityManager = activityManager,
            taskId = taskId
        ) ?: return null

        val packageName =
            taskInfo.baseActivity?.packageName
                ?: taskInfo.topActivity?.packageName
                ?: taskInfo.baseIntent?.component?.packageName
                ?: return null

        val userId = readTaskUserId(taskInfo)
        val uid = resolvePackageUidForUser(
            context = context,
            packageName = packageName,
            userId = userId
        ).takeIf { it >= 0 } ?: return null
        val label = resolveAppLabel(context, packageName)

        val baseIntent = taskInfo.baseIntent
            ?.let(::cloneWithPinTaskFlag)
        val launcherIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let(::cloneWithPinTaskFlag)

        val pinInfo = Bundle().apply {
            putInt("taskId", taskId)
            putString("packageName", packageName)
            if (baseIntent != null) {
                putParcelable("baseIntent", baseIntent)
            }
            if (launcherIntent != null) {
                putParcelable("launcherIntent", launcherIntent)
            }
            putInt("userId", userId)
            putInt("uid", uid)
            putInt("pid", 0)
        }

        val notification = buildSyntheticNotification(
            context = context,
            packageName = packageName,
            title = label,
            pinInfo = pinInfo
        )

        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(
            packageName,
            packageName,
            PIN_NOTIFICATION_ID,
            taskId.toString(),
            uid,
            0,
            0,
            notification,
            resolveUserHandle(userId),
            System.currentTimeMillis()
        )

        return PinRecord(
            taskId = taskId,
            packageName = packageName,
            sbn = sbn
        )
    }


    @Suppress("DEPRECATION")
    private fun findTaskInfo(
        activityManager: ActivityManager,
        taskId: Int
    ): TaskInfo? {
        val running = runCatching {
            activityManager.getRunningTasks(128)
                .firstOrNull { it.taskId == taskId }
        }.getOrNull()
        if (running != null) return running

        // A pinned task may have left the currently-running set while still being present in
        // Recents. SystemUI is privileged, so use the read-only recent-task list as fallback.
        return runCatching {
            activityManager.getRecentTasks(
                128,
                ActivityManager.RECENT_WITH_EXCLUDED
            ).firstOrNull { it.taskId == taskId }
        }.getOrNull()
    }

    private fun readTaskUserId(
        taskInfo: TaskInfo
    ): Int =
        runCatching {
            // getField() already walks public inherited fields, including TaskInfo.userId.
            val field = taskInfo.javaClass.getField("userId")
            (field.get(taskInfo) as? Number)?.toInt()
                ?: currentUserId()
        }.getOrDefault(currentUserId())

    private fun currentUserId(): Int =
        runCatching {
            val method = UserHandle::class.java.getDeclaredMethod(
                "getUserId",
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            (method.invoke(
                null,
                android.os.Process.myUid()
            ) as? Number)?.toInt() ?: 0
        }.getOrElse {
            // Android UID allocation uses a 100000-wide range per user.
            (android.os.Process.myUid() / 100_000).coerceAtLeast(0)
        }

    private fun resolvePackageUidForUser(
        context: Context,
        packageName: String,
        userId: Int
    ): Int =
        runCatching {
            val method = PackageManager::class.java.getMethod(
                "getPackageUidAsUser",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            (method.invoke(
                context.packageManager,
                packageName,
                userId
            ) as? Number)?.toInt() ?: -1
        }.recoverCatching {
            context.packageManager
                .getApplicationInfo(packageName, 0)
                .uid
        }.getOrDefault(-1)

    private fun buildSyntheticNotification(
        context: Context,
        packageName: String,
        title: String,
        pinInfo: Bundle
    ): Notification {
        val icon = resolveAppIcon(
            context,
            packageName
        )

        val liveAlertOptions = JSONObject().apply {
            put("isMilestone", true)
            put("dataSourcePkgName", packageName)
            put("remindType", 0)
            put(
                "showHostMap",
                JSONArray().apply {
                    put(1)
                    put(16)
                }
            )
            put("lockScreenShowHostMap", JSONArray())
            put(
                "extensibleActionMap",
                JSONObject().apply {
                    put("service_from", 3)
                    put("quitHideOrBubble", 0)
                }
            )
        }.toString()

        @Suppress("DEPRECATION")
        return Notification.Builder(
            context,
            PIN_NOTIFICATION_CHANNEL
        )
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(title)
            .setShowWhen(false)
            .build()
            .apply {
                extras.putBundle(
                    KEY_PIN_INFO,
                    pinInfo
                )
                extras.putString(
                    KEY_LIVE_ALERT_OPTIONS,
                    liveAlertOptions
                )
                extras.putCharSequence(
                    Notification.EXTRA_TITLE,
                    title
                )
                extras.putCharSequence(
                    Notification.EXTRA_TEXT,
                    title
                )
            }
    }


    private fun resolveAppLabel(
        context: Context,
        packageName: String
    ): String =
        runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(
                packageName,
                0
            )
            pm.getApplicationLabel(info)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
        }.getOrDefault(packageName)

    private fun resolveAppIcon(
        context: Context,
        packageName: String
    ): Icon =
        runCatching {
            val info = context.packageManager
                .getApplicationInfo(
                    packageName,
                    0
                )
            if (info.icon != 0) {
                Icon.createWithResource(
                    packageName,
                    info.icon
                )
            } else {
                Icon.createWithResource(
                    "android",
                    android.R.drawable.sym_def_app_icon
                )
            }
        }.getOrElse {
            Icon.createWithResource(
                "android",
                android.R.drawable.sym_def_app_icon
            )
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
                .setId("coloros17/fluidCompat/$id")
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

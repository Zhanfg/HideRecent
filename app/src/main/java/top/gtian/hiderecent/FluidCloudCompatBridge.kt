package top.gtian.hiderecent

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
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
    private const val KEY_RESULT = "resultCode"

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

                    if (legacyMethod == METHOD_UNPIN &&
                        resultCode != 0
                    ) {
                        val taskId =
                            legacyExtras.getInt("taskId", -1)
                        if (taskId >= 0) {
                            pinsByTaskId.remove(taskId)
                        }
                    }

                    result
                }
            }

        return count
    }

    private fun buildPinTranslation(
        module: Main,
        legacy: Bundle
    ): Pair<String, Bundle>? {
        val taskId = legacy.getInt("taskId", -1)
        val packageName = legacy.getString("packageName")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val userId = legacy.getInt("userId", 0)
        val uid = legacy.getInt("uid", -1)
        val pid = legacy.getInt("pid", 0)

        if (taskId < 0) return null

        val context = module.currentContext()
            ?: return null

        val pinInfo = Bundle(legacy)
        val label = resolveAppLabel(context, packageName)

        if (!pinInfo.containsKey("serviceTitleName")) {
            pinInfo.putString("serviceTitleName", label)
        }
        if (!pinInfo.containsKey("title")) {
            pinInfo.putString("title", label)
        }
        if (!pinInfo.containsKey("des")) {
            pinInfo.putString("des", label)
        }

        val baseIntent = runCatching {
            @Suppress("DEPRECATION")
            legacy.getParcelable("baseIntent") as? Intent
        }.getOrNull()

        if (!pinInfo.containsKey("componentName")) {
            baseIntent?.component
                ?.flattenToString()
                ?.let {
                    pinInfo.putString(
                        "componentName",
                        it
                    )
                }
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
            taskId,
            "launcher_pin_task_$taskId",
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

        val record = pinsByTaskId[taskId]
        if (record == null) {
            module.log(
                Log.WARN,
                TAG,
                "cancel requested but no synthetic SBN cached for taskId=$taskId"
            )
            return null
        }

        return METHOD_CANCEL to Bundle().apply {
            putParcelable(KEY_CONTENT, record.sbn)
        }
    }

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

        @Suppress("DEPRECATION")
        return Notification.Builder(context)
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

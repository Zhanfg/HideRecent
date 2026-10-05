package top.gtian.hiderecent

import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only Athena PinTask verification bridge for ColorOS 17.
 *
 * Verified from the extracted Athena build:
 * - FilterHelper.updateRecentLockListFromFw() forwards extra_info to updatePinLockListFromFw().
 * - process-clear code calls FilterHelper.isPinTask(...).
 * - when true, OEM clear logic logs "is pinned, skip to remove" and does not remove the task.
 *
 * This bridge NEVER changes Athena results, lock lists, freeze state, OOM adj or process policy.
 * It only observes low-frequency list updates and positive PinTask matches so we can validate
 * that Launcher -> SystemUI -> framework -> Athena propagation is actually working.
 */
internal object AthenaPinTaskProbe {
    private const val TAG = "${Main.TAG}/athenaPin"
    private const val PREF_RETRY_MS = 1_500L
    private const val PREF_MAX_ATTEMPTS = 40

    private data class Config(
        val enabled: Boolean = false
    )

    @Volatile private var config = Config()
    @Volatile private var remotePrefs: SharedPreferences? = null
    @Volatile private var prefsListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val bootstrapStarted = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val positiveLogCount = AtomicLong(0)
    private val lastMatchTraceAt = AtomicLong(0L)

    fun hook(
        module: Main,
        loader: ClassLoader
    ) {
        if (!bootstrapStarted.compareAndSet(false, true)) return
        val expectedGeneration = generation.incrementAndGet()

        val count = hookAthena(module, loader)
        module.log(
            if (count > 0) Log.INFO else Log.WARN,
            TAG,
            "Athena PinTask probe installed hooks=$count"
        )

        reportReadyAsync(
            module = module,
            expectedGeneration = expectedGeneration,
            hookCount = count
        )

        Thread({
            bootstrapPrefs(module, expectedGeneration)
        }, "LauncherStabilityAthenaPrefs").apply {
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
        positiveLogCount.set(0L)
        lastMatchTraceAt.set(0L)
    }

    private fun reportReadyAsync(
        module: Main,
        expectedGeneration: Int,
        hookCount: Int
    ) {
        Thread({
            repeat(24) {
                if (generation.get() != expectedGeneration) return@Thread
                val context = module.currentContext()
                if (context != null) {
                    PinTaskRuntimeTraceReporter.record(
                        context = context,
                        stage = PinTaskRuntimeTrace.STAGE_ATHENA_READY,
                        detail = "Athena injected; hooks=" + hookCount
                    )
                    return@Thread
                }
                SystemClock.sleep(250L)
            }
        }, "LauncherStabilityAthenaReady").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun bootstrapPrefs(
        module: Main,
        expectedGeneration: Int
    ) {
        SystemClock.sleep(1_000L)

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
                    stage = PinTaskRuntimeTrace.STAGE_ATHENA_READY,
                    detail = "Athena hook ready; enabled=${config.enabled}"
                )
                return
            }

            SystemClock.sleep(PREF_RETRY_MS)
        }

        module.log(
            Log.WARN,
            TAG,
            "remote prefs unavailable; Athena probe remains silent"
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
            positiveLogCount.set(0L)
        }
    }

    private fun hookAthena(
        module: Main,
        loader: ClassLoader
    ): Int {
        val cls = runCatching {
            Class.forName(
                "com.oplus.athena.common.parser.athena.FilterHelper",
                false,
                loader
            )
        }.getOrNull() ?: return 0

        var count = 0

        cls.declaredMethods
            .filter {
                it.name == "updateRecentLockListFromFw" &&
                    it.parameterTypes.size == 2 &&
                    Bundle::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "fwIngress/$index"
                ) { chain ->
                    if (config.enabled) {
                        val extras = chain.args.firstOrNull() as? Bundle
                        val userId =
                            (chain.args.getOrNull(1) as? Number)?.toInt()

                        PinTaskRuntimeTraceReporter.record(
                            context = module.currentContext(),
                            stage = PinTaskRuntimeTrace.STAGE_ATHENA_INGRESS,
                            detail = "updateRecentLockListFromFw; user=" +
                                userId + "; keys=" +
                                extras?.keySet()?.sorted()
                        )
                    }
                    chain.proceed()
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "updatePinLockListFromFw" &&
                    it.parameterTypes.size == 2 &&
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "pinList/$index"
                ) { chain ->
                    val result = chain.proceed()

                    if (config.enabled) {
                        val list = chain.args.firstOrNull() as? List<*>
                        val userId =
                            (chain.args.getOrNull(1) as? Number)?.toInt()

                        module.log(
                            Log.INFO,
                            TAG,
                            "Athena pin lock list updated user=$userId " +
                                "count=${list?.size ?: -1}"
                        )

                        PinTaskRuntimeTraceReporter.record(
                            context = module.currentContext(),
                            stage = PinTaskRuntimeTrace.STAGE_ATHENA_LIST,
                            detail = "pin lock list updated; user=" +
                                userId + "; count=" +
                                (list?.size ?: -1)
                        )
                    }

                    result
                }
            }

        cls.declaredMethods
            .filter {
                it.name == "isPinTask" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            .forEachIndexed { index, method ->
                count += hook(
                    module,
                    method,
                    "isPinTask/$index"
                ) { chain ->
                    val stock = chain.proceed() as? Boolean ?: false

                    if (config.enabled && stock) {
                        val n = positiveLogCount.getAndIncrement()
                        if (n < 24L) {
                            val first =
                                (chain.args.getOrNull(0) as? Number)?.toInt()
                            val second =
                                (chain.args.getOrNull(1) as? Number)?.toInt()

                            module.log(
                                Log.INFO,
                                TAG,
                                "Athena isPinTask=true arg0=$first arg1=$second; " +
                                    "OEM result preserved"
                            )

                            val now = SystemClock.elapsedRealtime()
                            val previous = lastMatchTraceAt.get()
                            if (now - previous >= 750L &&
                                lastMatchTraceAt.compareAndSet(previous, now)
                            ) {
                                PinTaskRuntimeTraceReporter.record(
                                    context = module.currentContext(),
                                    stage = PinTaskRuntimeTrace.STAGE_ATHENA_MATCH,
                                    detail = "isPinTask=true; rawArg0=" +
                                        first + "; rawArg1=" + second
                                )
                            }
                        }
                    }

                    stock
                }
            }

        return count
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
                .setId("coloros17/athenaPin/$id")
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
}

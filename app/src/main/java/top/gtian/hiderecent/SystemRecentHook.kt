package top.gtian.hiderecent

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 注入 system_server（android 作用域）。
 * 参考 hideRecent / RocGwei：拦截 com.android.server.wm.RecentTasks.isVisibleRecentTask
 * 使指定包名的任务不出现在最近任务列表。
 */
object SystemRecentHook {

    private const val TAG = "${Main.TAG}/sys"
    private const val SNAPSHOT_REFRESH_SEC = 15L
    private val hiddenSnapshot = HiddenPackagesSnapshot()
    private val refreshStarted = AtomicBoolean(false)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HideRecentTiles-sys-refresh").apply { isDaemon = true }
    }

    fun hook(module: Main, cl: ClassLoader) {
        // 目标类在部分 ROM 上是 RecentTasks 内部类，回退 Task 基类
        val recentTasks = loadClass(cl,
            "com.android.server.wm.RecentTasks",
            "com.android.server.wm.RecentTasks\$1"
        ) ?: run {
            module.log(Log.ERROR, TAG, "RecentTasks not found")
            return
        }

        var count = 0
        // 单参重载（AOSP）
        count += hookBool(module, recentTasks, "isVisibleRecentTask", 1)
        // 双参重载（Vivo / 部分 OEM 直接调用）
        count += hookBool(module, recentTasks, "isVisibleRecentTask", 2)
        startSnapshotRefresh(module)
        module.log(Log.INFO, TAG, "isVisibleRecentTask hooks = $count")
    }

    /** Stop the old generation's scheduler before API 102 replaces its hooks. */
    fun prepareHotReload(module: Main) {
        runCatching { refreshExecutor.shutdownNow() }
            .onFailure { module.log(Log.WARN, TAG, "hot reload executor cleanup failed: ${it.message}") }
    }

    /** 拦截返回 boolean 的方法：若目标任务包名在隐藏集合内则强制返回 false */
    private fun hookBool(module: Main, cls: Class<*>, name: String, argCount: Int): Int {
        val method = findMethod(cls, name, argCount) ?: return 0
        module.hook(method).setId("$name/$argCount").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "hook fallback: ${it.message}") }
            ) {
                val hidden = hiddenSnapshot.get()
                if (hidden.isNotEmpty()) {
                    val pkg = packageNameOf(chain.args)
                    if (pkg != null && pkg in hidden) {
                        return@evaluateOrProceed false
                    }
                }
                null
            }
        }
        return 1
    }

    private fun startSnapshotRefresh(module: Main) {
        if (!refreshStarted.compareAndSet(false, true)) return
        refreshExecutor.scheduleWithFixedDelay({
            runCatching {
                hiddenSnapshot.replace(module.hiddenPackages)
            }.onFailure {
                module.log(Log.WARN, TAG, "snapshot refresh failed: ${it.message}")
            }
        // ponytail: 首次延迟一个周期,避免开机早期从 system_server 发 provider query
        // 去冷启动模块进程(可能阻塞 system_server)。升级路径:改为监听广播、彻底去掉轮询。
        }, SNAPSHOT_REFRESH_SEC, SNAPSHOT_REFRESH_SEC, TimeUnit.SECONDS)
    }

    /** 从方法入参里的 Task 对象取基础 Intent 的包名 */
    private fun packageNameOf(args: List<Any?>): String? {
        val task = args.firstOrNull() ?: return null
        return try {
            val m = task.javaClass.getMethod("getBaseIntent")
            val intent = m.invoke(task) as? android.content.Intent ?: return null
            intent.component?.packageName ?: intent.`package`
        } catch (t: Throwable) {
            try {
                // 部分 ROM 用 mBaseIntent 字段
                val f = task.javaClass.getDeclaredField("mBaseIntent").apply { isAccessible = true }
                (f.get(task) as? android.content.Intent)?.component?.packageName
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? =
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == argCount }
            ?.apply { isAccessible = true }

    private fun loadClass(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            try {
                return cl.loadClass(n)
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
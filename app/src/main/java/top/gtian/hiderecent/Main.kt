package top.gtian.hiderecent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern Xposed API (102) 模块入口。
 * 框架要求：无参构造，入口全类名写在 META-INF/xposed/java_init.list。
 */
class Main : XposedModule() {

    companion object {
        const val TAG = "HideRecentTiles"
        const val MODULE_PKG = "top.gtian.hiderecent"
        const val PREFS_NAME = "hide_recent"
        const val KEY_HIDE = "hide_list"

        /**
         * 配置变更广播：UI 写盘后发出，launcher 进程里动态注册的 receiver 收到即刷新缓存。
         * 这是唯一不依赖模块进程存活的通知通道——模块进程被最近任务划掉后依然生效。
         */
        const val ACTION_PREFS_CHANGED = "top.gtian.hiderecent.PREFS_CHANGED"
        const val EXTRA_HIDE = "hide_list"

        /**
         * signature 级权限：仅同签名应用（即模块自身）可发送配置变更广播，
         * 防止第三方应用伪造广播篡改隐藏名单。
         */
        const val PREFS_PERMISSION = "top.gtian.hiderecent.permission.PREFS"

        /** 缓存有效期：期内直接返回缓存，不再做任何 IPC 读取（hook 可能每秒被调上百次） */
        private const val CACHE_TTL_MS = 1500L

        /** ColorOS 桌面进程列表（launcher 侧 hook 作用域） */
        val LAUNCHER_PKGS = setOf(
            "com.oplus.quickstep",
            "com.android.launcher",
            "com.oplus.launcher"
        )

        /**
         * 模块 UI 用普通 SharedPreferences("hide_recent") 写入（与 getRemotePreferences 同存储）。
         * 兜底通道：直读模块 data 目录的 XML（跨 UID 通常无权限，仅作最后尝试）。
         */
        val PREFS_FILE = "/data/data/$MODULE_PKG/shared_prefs/$PREFS_NAME.xml"

        /**
         * 通道 4：**hook 所在进程自己**的落盘缓存。
         *
         * 存在本进程私有 filesDir（launcher / system_server），不依赖模块进程存活、不依赖 Binder。
         * 模块被最近任务划掉后，这是唯一还能给出名单的通道——这正是「杀掉模块进程后隐藏失效」的根因所在。
         * 内容格式与 prefs 一致（逗号分隔）；空文件表示「合法的空名单」。
         */
        private const val DISK_CACHE_NAME = "hidden_cache.txt"

        /**
         * UI 侧写盘后调用：把最新名单直接推给 launcher 进程。
         *
         * 权限方向（很容易搞反，这里必须这样写）：
         * - 接收方用 `registerReceiver(..., broadcastPermission = PREFS_PERMISSION, ...)` 注册，
         *   含义是「**发送方必须持有**该权限」——模块自身声明并持有它，第三方应用没有，所以无法伪造。
         * - 因此这里**不能**再写 `sendBroadcast(intent, PREFS_PERMISSION)`：那个参数的含义是
         *   「**接收方必须持有**该权限」，而桌面（com.android.launcher / com.oplus.quickstep）
         *   并没有持有模块的 signature 权限，一旦加上广播就会被系统直接丢弃、根本收不到。
         */
        fun notifyPrefsChanged(ctx: Context, hidden: Set<String>) {
            runCatching {
                val intent = Intent(ACTION_PREFS_CHANGED)
                    .putExtra(EXTRA_HIDE, hidden.joinToString(","))
                ctx.sendBroadcast(intent)
            }.onFailure { Log.w(TAG, "notify prefs changed failed", it) }
        }
    }

    /**
     * 最近一次**成功**解析出的隐藏集合。
     *
     * 语义关键：只有通道成功应答才会写入这里，**读取失败绝不写入**。
     * 模块进程被最近任务划掉后 ContentProvider 通道会失败；若此时把「失败」当成「空名单」返回，
     * 所有被隐藏的应用就会重新出现在最近任务里——这正是此前的 bug。
     */
    @Volatile
    private var cachedHidden: Set<String>? = null

    @Volatile
    private var cachedAt = 0L

    /** libxposed remote preferences 实例复用（避免每次 hook 都走 Binder 构造） */
    @Volatile
    private var remotePrefsRef: SharedPreferences? = null

    @Volatile
    private var remotePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** UI 勾选的隐藏包名集合（每次 hook 调用时读取，改配置即时生效） */
    val hiddenPackages: Set<String>
        get() = readHiddenPackages()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded in ${param.processName}")
    }

    /** system_server 启动早期 hook 最近任务可见性 */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching { SystemRecentHook.hook(this, param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "system hook failed", it) }
    }

    /** ColorOS 桌面进程：hook 最近任务过滤点 + 注册配置变更广播接收器 */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.packageName !in LAUNCHER_PKGS) return
        runCatching { LauncherRecentHook.hook(this, param) }
            .onFailure { log(Log.ERROR, TAG, "launcher hook failed", it) }
    }

    /** API 102 module-code hot reload: detach the old generation before replacing hooks. */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // Keep only classloader-neutral state across generations.
        param.setSavedInstanceState(cachedHidden?.joinToString(",") ?: "")
        runCatching { LauncherRecentHook.prepareHotReload(this) }
        runCatching { SystemRecentHook.prepareHotReload(this) }
        detachRemotePrefsListener()
        log(Log.INFO, TAG, "hot reload: old generation detached")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        (param.getSavedInstanceState() as? String)?.let { raw ->
            cachedHidden = parse(raw)
            cachedAt = SystemClock.elapsedRealtime()
        }

        val oldHandles = param.oldHookHandles.toList()
        val loader = oldHandles.asSequence()
            .mapNotNull { it.executable.declaringClass.classLoader }
            .firstOrNull()
            ?: if (param.isSystemServer) ClassLoader.getSystemClassLoader()
            else currentContext()?.classLoader

        // Remove old-generation callbacks first so the previous module ClassLoader can be released.
        oldHandles.forEach { runCatching { it.unhook() } }

        if (loader != null) {
            if (param.isSystemServer) {
                runCatching { SystemRecentHook.hook(this, loader) }
                    .onFailure { log(Log.ERROR, TAG, "hot reload system rehook failed", it) }
            } else if (param.processName.substringBefore(':') in LAUNCHER_PKGS) {
                runCatching { LauncherRecentHook.hook(this, loader) }
                    .onFailure { log(Log.ERROR, TAG, "hot reload launcher rehook failed", it) }
            }
        } else {
            log(Log.ERROR, TAG, "hot reload: target classloader unavailable")
        }

        log(Log.INFO, TAG, "hot reload complete; oldHandles=${oldHandles.size}")
    }

    /**
     * 广播推送入口：模块 UI 写盘后直接把最新名单推过来，**零 IPC**。
     * 由 launcher 进程里动态注册的 receiver 调用；这样即使模块进程已被划掉，
     * 隐藏名单依然是最新的。
     */
    fun onPrefsPushed(raw: String?) {
        val set = parse(raw)
        cachedHidden = set
        cachedAt = SystemClock.elapsedRealtime()
        persistDiskCache(set)
        log(Log.INFO, TAG, "prefs pushed: ${set.size} ${set.take(5)}")
    }

    /**
     * 让缓存失效，下次 hook 调用重新走一遍读取通道。
     *
     * 广播里没带名单时调用（例如写盘还没落盘、或发送方是别的模块实例）。
     * 注意不能用 cachedAt = 0L 之外的“脏标记”方式绕过 TTL——这里直接把缓存清掉，
     * 保证下一次读取一定会命中真实通道，而不是沿用旧名单。
     */
    fun invalidateCache(forceReload: Boolean) {
        cachedAt = 0L
        if (forceReload) cachedHidden = null
    }

    /**
     * 读取 UI 勾选的隐藏集合（hook 侧唯一入口）。
     *
     * 用 String（逗号分隔）而非 StringSet——StringSet 跨进程读取有已知兼容性问题。
     * 通道优先级（**活跃通道优先，落盘缓存兜底**——反过来会让陈旧值永久生效）：
     * 1) ContentProvider（模块进程存活时最准，永远反映最新勾选）
     * 2) libxposed remote preferences（不依赖模块进程，但快照可能陈旧）
     * 3) 兜底：直读模块 data 目录的 SharedPreferences XML（跨 UID 通常无权限）
     * 4) 上面全部无应答（典型场景：模块进程被最近任务划掉）→ 本进程落盘缓存
     *
     * 任一级返回 null 表示「该通道没有应答」，继续下一级；
     * 全部失败则**沿用上一次成功结果**，绝不退化成空集。
     */
    private fun readHiddenPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedHidden
        if (cached != null && now - cachedAt < CACHE_TTL_MS) return cached

        val fresh = queryProvider() ?: queryRemote() ?: queryPrefsFile()
        if (fresh != null) {
            if (fresh != cached) {
                log(Log.INFO, TAG, "hidden updated: ${fresh.size} ${fresh.take(5)}")
                // 每次拿到真实值就落盘，供模块进程死掉后兜底；值没变则不写盘
                persistDiskCache(fresh)
            }
            cachedHidden = fresh
            cachedAt = now
            return fresh
        }

        // 模块进程已被划掉：provider / remote / XML 全部无应答，退到本进程落盘缓存
        val disk = queryDiskCache()
        if (disk != null) {
            if (disk != cached) {
                log(Log.INFO, TAG, "fallback disk cache: ${disk.size} ${disk.take(5)}")
            }
            cachedHidden = disk
            cachedAt = now
            return disk
        }

        // 连落盘缓存都没有（首次运行 / 缓存文件被清）：只能沿用内存缓存，绝不返回空集
        log(Log.WARN, TAG, "all prefs channels failed, keep cache(${cached?.size})")
        return cached ?: emptySet()
    }

    /** 通道 1：ContentProvider（跨进程直查模块 provider，模块进程被拉起服务查询） */
    private fun queryProvider(): Set<String>? {
        return try {
            val ctx = currentContext() ?: return null
            val cursor = ctx.contentResolver.query(PrefsProvider.URI, null, null, null, null)
                ?: return null
            cursor.use {
                if (!it.moveToFirst()) return null
                // 空串是「合法的空名单」（用户全部取消勾选），不是失败
                return parse(it.getString(0))
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "provider prefs failed: ${t.message}")
            null
        }
    }

    /**
     * 通道 2：libxposed remote preferences（不依赖模块进程）。
     *
     * 注意**不能**写成 `parse(prefs.getString(KEY_HIDE, null))`：
     * [parse] 对 null 返回空集（非 null），于是「键不存在」会被当成一次成功的空名单应答，
     * 直接终止链式查找，磁盘缓存那一级永远不会被走到——这就是杀进程后隐藏失效的根因。
     * 键不存在 = 该通道没有应答，必须返回 null 继续下一级。
     * 空串则是「合法的空名单」（UI 侧统一写入空串而非删键，见 MainActivity）。
     */
    private fun queryRemote(): Set<String>? {
        return try {
            val prefs = remotePrefs() ?: return null
            val raw = prefs.getString(KEY_HIDE, null) ?: return null
            parse(raw)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "remote prefs failed: ${t.message}")
            null
        }
    }

    /** 通道 3：兜底直读 XML（跨 UID 通常无权限，失败返回 null） */
    private fun queryPrefsFile(): Set<String>? {
        return try {
            val text = java.io.File(PREFS_FILE).readText()
            val raw = Regex("<string name=\"hide_list\">([^<]*)</string>")
                .find(text)?.groupValues?.get(1) ?: return null
            parse(raw)
        } catch (_: Throwable) {
            null
        }
    }

    /** 通道 4 写入：把本次成功读到的名单落盘到**本进程**私有目录，供模块进程死掉后自救 */
    private fun persistDiskCache(hidden: Set<String>) {
        try {
            val target = diskCacheFile() ?: return
            val text = hidden.joinToString(",")
            // 先写临时文件再改名，避免 hook 读到写了一半的内容
            val tmp = java.io.File(target.parentFile, "$DISK_CACHE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "disk cache write failed: ${t.message}")
        }
    }

    /**
     * 通道 4 读取：本进程上一次落盘的名单。
     * 文件不存在返回 null（表示该通道没有应答）；空文件是「合法的空名单」。
     */
    private fun queryDiskCache(): Set<String>? {
        return try {
            val f = diskCacheFile() ?: return null
            if (!f.isFile) return null
            parse(f.readText())
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "disk cache read failed: ${t.message}")
            null
        }
    }

    /** 本进程 filesDir 下的缓存文件；路径缓存复用，避免每次 hook 都反射取 Context */
    @Volatile
    private var diskCacheFileRef: java.io.File? = null

    private fun diskCacheFile(): java.io.File? {
        diskCacheFileRef?.let { return it }
        synchronized(this) {
            diskCacheFileRef?.let { return it }
            return try {
                val dir = currentContext()?.filesDir
                if (dir == null) null
                else java.io.File(dir, DISK_CACHE_NAME).also { diskCacheFileRef = it }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 复用 remote prefs 实例，并注册变更监听（框架推送即让缓存过期，下次读取刷新） */
    private fun remotePrefs(): SharedPreferences? {
        remotePrefsRef?.let { return it }
        return synchronized(this) {
            remotePrefsRef ?: try {
                getRemotePreferences(PREFS_NAME)?.also { prefs ->
                    remotePrefsRef = prefs
                    runCatching {
                        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                            cachedAt = 0L
                        }
                        remotePrefsListener = listener
                        prefs.registerOnSharedPreferenceChangeListener(listener)
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun detachRemotePrefsListener() {
        val prefs = remotePrefsRef
        val listener = remotePrefsListener
        if (prefs != null && listener != null) {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        remotePrefsListener = null
        remotePrefsRef = null
    }

    /** 逗号分隔字符串 → 包名集合；null / 空串都表示「合法的空名单」 */
    private fun parse(raw: String?): Set<String> =
        if (raw.isNullOrEmpty()) emptySet()
        else raw.split(',').filter { it.isNotEmpty() }.toSet()

    /** 通过 ActivityThread.currentApplication() 获取 Context（launcher 侧注册广播用） */
    fun currentContext(): Context? {
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            val method = cls.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }
}

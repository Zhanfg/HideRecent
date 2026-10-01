# HideRecent v26.10.1-generic1 — 通用最近任务隐藏 + ColorOS 增强 + 热重载

基线：`GTian5418/HideRecent` main `992abf382914b670674caee064cbf164ff0deb31`，叠加上一轮 v26.10.1 ColorOS 16/热重载修复。

## 这一轮新增：通用而非 ColorOS 专用

### 1. system_server 通用层（主路径）
- Hook AOSP `com.android.server.wm.RecentTasks.isVisibleRecentTask(...)`。
- 额外 Hook `RecentTasks.getRecentTasksImpl(...)`，对最终 `ArrayList<RecentTaskInfo>` 再过滤一遍。
- 使用 `RecentTaskPackages` 从 `baseIntent/baseActivity/topActivity/origActivity/realActivity` 等标准 TaskInfo 字段解析包名。
- 这层不依赖 Launcher 厂商实现，是 Android 10+ 最通用的基础路径。

### 2. AOSP Launcher3 / Quickstep 通用层
- Hook `com.android.quickstep.RecentTasksList.loadTasksInBackground(...)`。
- 同时兼容：
  - 老版本 `ArrayList<Task>`；
  - 新版本私有 `TaskLoadResult : ArrayList<GroupTask>`；
  - split/grouped task；
  - desktop/freeform group。
- 采用“原地删除”而不是替换返回类型，避免私有 `TaskLoadResult` 被换成普通 ArrayList 后发生 ClassCastException。
- Hook `getRunningTasks()` 返回过滤副本，覆盖任务栏/桌面模式/Quick Switch 等运行任务入口，同时不修改 Launcher 内部 backing list。

### 3. OEM 兼容层
- ColorOS 16 `OplusRecentTasksFilter.filterTaskInfo(GroupedTaskInfo)` 继续保留。
- 旧 ColorOS `filterTask(GroupTask)` 继续保留。
- OPlus 现在只是增强层，不再是模块成立的前提。

### 4. Scope 与运行时探测
默认 scope 增加：
- `android` / `system`
- `com.android.launcher`
- `com.android.launcher3`
- `com.google.android.apps.nexuslauncher`
- `com.android.systemui`
- 原有 OPlus launcher/quickstep

`staticScope=false` 保持不变。对于其它 OEM Launcher，可在 LSPosed 中手工把其桌面/Quickstep 包加入作用域；模块不再按 ColorOS 包名硬编码拦截，而会自动探测 `RecentTasksList`/OPlus 兼容类。

### 5. 继续保留上一轮能力
- API 102 `autoHotReload=true` 模块代码热重载。
- RemotePreferences 冷启动恢复。
- 配置改动后即时刷新 Launcher 最近任务缓存。
- 关键方法 `deoptimize()`。
- 分屏/组合任务任一成员命中隐藏名单即隐藏整张卡片。

## 版本
- versionName: `v26.10.1-generic1`
- versionCode: `2026100101`

## 验证重点
- `RecentTaskPackages`：AOSP RecentTaskInfo / GroupTask / GroupedTaskInfo 包名解析。
- `RecentTaskListFilter`：原地过滤不改变私有 List 子类返回类型。
- running tasks：返回过滤副本，不污染 Launcher 内部状态。
- system_server：visibility predicate + final task list 双保险。

GitHub 连接目前仍是只读（push=false），因此无法替你推分支或触发 Actions。建议后续分支名：`feat/generic-recents-filter`。

## 本轮本地验证结果
- API 102 / Android stub 全量 Hook Kotlin 编译：PASS
- AOSP `RecentTaskInfo` 原地过滤 smoke test：PASS
- split/grouped task 任一成员命中过滤 smoke test：PASS
- running-task filtered copy 不修改 backing list：PASS
- Manifest / scope.list / module.prop / versionCode 静态检查：PASS

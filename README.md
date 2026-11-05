# Dungeon 0.1.0

Kotlin + Maven 的 RPG 副本 MVP。架构与阶段计划见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。这不是所有远期功能均已完成的版本；下列支持范围与限制属于交付契约。

## 构建与安装

要求 Java 21、Maven 3.9+。执行 `mvn clean package`，安装 `target/Dungeon-0.1.0.jar`，不要安装 `original-*.jar`。Paper API 为 provided，Kotlin 与 SQLite JDBC 随包提供。SQLite 数据库线程串行执行，IO 线程池独立于所有区域线程。

基线 Minecraft 1.21.11。没有在真实 Folia/Canvas/Arbor 服务端运行验收，不能据编译成功声称运行时兼容。

| 平台 | 常驻已加载世界 | 临时世界 |
| --- | --- | --- |
| Folia | 使用区域/实体调度 API | 拒绝：没有已验证的运行时世界 API |
| Canvas 1.21.11 | 使用相同区域/实体调度 API | 能力探测通过后使用官方 global 创建与异步卸载回调 |
| Arbor | 保守使用相同区域/实体调度 API，待具体构建实测 | 默认拒绝，需匹配构建的 WorldProvider |
| Paper 1.21.11 | 使用 Paper 提供的区域调度兼容接口 | 当前默认提供器仅开放 Canvas 动态能力 |

Canvas 依据：https://docs.canvasmc.io/canvas/developers/api/world-loading/
MythicMobs 调用依据：https://mythiccraft.io/javadocs/mythic/io/lumine/mythic/bukkit/BukkitAPIHelper.html
已使用用户本地 MythicMobs-5.13.0.jar（plugin.yml 版本 5.13.0-aad3f1c9）通过 javap 核对 inst/getAPIHelper/getMobManager/getMythicMob 与 spawnMythicMob(String,Location) 签名及调用路径。反射通过 MM 插件自身 ClassLoader 加载，缺失依赖或怪物 ID 时在入场绑定前拒绝并记录原因；运行中的失败走副本清理。尚未进行真实服务器 MM 刷怪和跨区域实测。

首次启动异步安装默认文件，控制台出现 Dungeon 版本/平台/提供器日志后可使用命令。先编辑示例的坐标与世界名，确保脚下有安全落点；示例不自动改造世界地形。

## MythicMobs 示例安装

默认 example 已使用 MM 内部 ID `DungeonExampleGuard`，普通难度生成 3 只，困难难度 6 只；击杀后仍按原流程右键祭坛通关。守卫 30 生命、3 伤害，戴铁头盔，禁止默认掉落；个人通关与首通奖励仍由 Dungeon 管理。

1. 安装 MythicMobs 5.13.0。Dungeon 首次安装会导出 `plugins/Dungeon/integrations/mythicmobs/DungeonExample.yml`，将它复制到 `plugins/MythicMobs/Mobs/DungeonExample.yml`。不要把此文件放入 Dungeon 的 dungeons 目录。
2. 执行 `/mm reload`，检查 MM 控制台日志，确认加载 `DungeonExampleGuard`。外部 MM 配置没有 Dungeon 的 schema-version 字段，资源版本记录在注释里。
3. 已安装旧版 Dungeon 的服务器不会被自动覆盖配置。需在 `plugins/Dungeon/dungeons/example/mobs.yml` 的 `mobs.guard` 下，将 `type` 改为 `'DungeonExampleGuard'`、`mythic` 改为 `true`，保留原有坐标引用和数量。运行 `/dungeon reload example`。
4. 确认世界与坐标安全后执行 `/dungeon test example normal`。只加载配置并不证明实际刷怪、MM AI、死亡事件和区域线程兼容已通过。

## 常驻大厅地图

用户提供的 `C:\Users\32633\Desktop\新模型\副本大厅\完整版\Starfall_Dungeon_Hub` 已放入插件资源。地图实际 `DataVersion=4903`、目标 Minecraft 26.2，出生点 `0.5 101 20.5`。启动时先查找已加载世界，再将地图维度数据导入主世界的注册维度目录。压缩地图附逐文件 SHA-256 清单，复制到隔离暂存目录完成校验后才发布。归属标记保存在 `plugins/Dungeon/lobby/`，避免服务端移除维度目录内的未知标记。已存在但没有匹配 Dungeon 标记的维度拒绝覆盖；大厅受永久世界保护，不进入临时实例清理流程。

大厅要求服务端数据版本至少为 4903。更早版本会保留地图并拒绝加载。Folia 或未知区域线程服务端需先由 Worlds 或服务端启动配置加载世界，Dungeon 只绑定已加载的永久地图。Leaf 26.3 已通过隔离服务器启动，确认插件加载、世界格式迁移和大厅就绪。使用 `/dungeon lobby` 前往出生点；建筑内的副本门、组队区和排行榜区目前是场景预留位，对应玩法仍待开发。

此次未向用户服务器的 plugins 目录写入、替换插件或执行重载。配置与适配代码在工程及新构建 JAR 中交付。

## 命令和权限

普通功能统一权限 `dungeon.use`，默认所有玩家拥有；管理权限 `dungeon.admin`，默认 OP。命令本身不设置额外隐藏权限。

| 命令 | 权限 | 行为 |
| --- | --- | --- |
| `/dungeon list` | dungeon.use | 已加载副本 ID |
| `/dungeon enter <ID> [normal/hard]` | dungeon.use | 队长带在线队员进入，检查各成员权限与人数 |
| `/dungeon exit` | dungeon.use | 异步返回进入前的位置，成功后才解绑 |
| `/dungeon status` | dungeon.use | 实例 ID、状态和阶段 |
| `/dungeon party invite <玩家>` | dungeon.use | 发出 60 秒邀请 |
| `/dungeon party accept` | dungeon.use | 接受邀请 |
| `/dungeon party leave` | dungeon.use | 离队；队长退出解散队伍 |
| `/dungeon create <ID>` | dungeon.admin | 从默认示例创建实际完整配置目录并校验 |
| `/dungeon reload [ID]` | dungeon.admin | IO 线程加载并校验后原子替换；运行实例仍用原定义 |
| `/dungeon validate [ID]` | dungeon.admin | 解析与引用检查，不替换运行快照 |
| `/dungeon test <ID> [难度]` | dungeon.admin | 真正以单人开始一次副本，适用正常人数规则 |
| `/dungeon debug` | dungeon.admin | 当前实例、世界、状态与计数 |

管理指令不跳过普通玩法的安全检查。测试命令会生成怪物、发放奖励；请使用测试地图。

## 配置

每个副本目录为 `plugins/Dungeon/dungeons/<id>/`，包含 `dungeon.yml`、`stages.yml`、`mobs.yml`、`obstacles.yml`、`rewards.yml`、`locations.yml`，可选 `scripts/events.yml`。ID 使用小写字母、数字、下划线、短横线。

- `PERMANENT`：已有世界；独立世界须先由服务端加载。`PARTY` 对同一世界独占，避免没有物理隔离的队伍互相干扰；`SHARED` 允许后续队伍加入同一运行进度，容量仍受 max-players 限制。
- `TEMPORARY`：从 `plugins/Dungeon/templates/<template>/` 的离线地图复制到服务端世界根目录 `dungeon_<UUID>`。复制排除 uid.dat/session.lock；不支持时在复制前拒绝操作。
- `STORY`：单人阶段检查点续玩；中断的阶段重新刷怪，完成的阶段不重做。剧情进度保存到数据库，与地图是否存在无关。
- `campaign`、`chapter`：每个玩家初始解锁第一章；同一 campaign 通关第 N 章解锁 N+1 章。
- 目标 `KILL`/`BOSS` 引用 mobs ID，仅插件生成且属于当前实例/阶段的怪物计数；`EXPLORE`/`INTERACT` 引用点位（每玩家每阶段一次）；`COLLECT` 引用材质，消耗达到目标所需数量，余量保留。
- `ESCORT` 引用 mobs ID，必须在本阶段 mobs 列表中，配置 destination 为目标点位、count 为 1；实体调度器每秒导航并检查到达，连续 20 次无路径结束副本。
- `obstacles.yml` 的 damage 定义右键点位时的伤害；默认示例为祭坛机关。
- 难度继承通过 `difficulties.<id>.extends`，当前支持一层继承，倍率影响怪物数量及击杀/收集数量；探索和交互不额外倍增。
- 公共配置复用：任意功能 YAML 顶层 `extends: 'base'`，从 `plugins/Dungeon/components/base/<同名文件>` 继承；支持至多 8 层并检测环。子文件覆盖对应叶字段/整列表。
- 所有 YAML 带 schema-version。已定义的旧版本 0 与 1 字段语义相同，迁移只改版本标记，先保存 .v0.bak，再原子替换，保留未知字段和注释；未知版本拒绝加载，原文件保留。
- 重载校验失败保留全部旧快照，控制台报告目录、文件或无效引用。配置编辑后用 validate 再 reload。

## 脚本扩展

`scripts/events.yml` 为声明式 Trigger + Condition + Action，不执行任意 JavaScript/Kotlin。触发器 started/stage/completed；条件 state/stage；动作 message 的 argument 引用 lang/zh_CN.yml 的键。每动作可指定 delay-ticks。

每规则最多 16 个条件、64 个动作，总延迟上限 600 tick；事件最多 64 规则。单次执行 30 秒截止，实例关闭或阶段变化使迟到动作失效，不支持无界循环。`ScriptEngine` 提供 registerAction/registerCondition 注册入口；扩展动作必须自行通过所有权调度器访问平台。初始化完成后可通过 Bukkit ServicesManager 获取 ScriptEngine，注册事件、条件、动作，再单副本重载引用这些扩展的配置。注册事件的适配器负责调用 fire；当前未附带第三方脚本包。

## 实例安全与恢复

玩家位置先写数据库，再开始入场传送。成员均成功返回且没有在途传送后，才发起世界卸载；只有卸载回调后复核世界已从注册表移除，才删除匹配 UUID 标记的专属目录。当前配置中所有常驻世界额外列入保护集合。模板不允许符号链接。

断线暂停队伍，重连恢复；超过宽限期结束。宽限期内保留离线成员绑定；到期后释放成员引用，保留数据库返回记录，重连走恢复流程。卸载失败每 10 秒重试，最多追加 3 次，随后保留文件并记录诊断，避免删除仍加载的世界。

重启后恢复玩家返回位置、剧情检查点、章节、通关与奖励记录；不恢复正在战斗的临时实例。启动时只清理未加载且有本插件所有权标记的残留实例；已加载实例保留并输出日志，避免登录/传送竞态。停服保留世界文件；普通 JVM 退出不会依赖插件同步复制或等待数据库。插件热卸载/热重载不作为支持流程，请完整重启服务器；远区域实体清理在停用阶段尚未有运行证据，生成怪物设置为不持久化。

## 奖励与审计

通关记录、下一章解锁和奖励 RESERVED 唯一记录在一个 SQLite 事务中提交；之后回到玩家实体调度器发放普通物品并标记 DELIVERED。`completion` 每次通关发放，`first-completion` 每位玩家每副本首次额外发放。

崩溃、断线或背包不足可能留下 RESERVED：此状态禁止自动重发，避免重复发奖。SQLite 与游戏背包不是同一个事务，不能宣称跨系统 exactly-once。请停服备份数据库后由管理员核查 `rewards` 表，结合 `completions` 判断是否补发；不要直接批量删除 RESERVED。当前没有自动补领和后台审计界面。

## 验证范围与尚未实现

本地覆盖状态机并发去重/迟到事件、暂停、奖励唯一约束与重开数据库、通关解锁事务、模板保护删除、配置引用/失败回滚、脚本取消、迁移幂等和大厅归档校验/不覆盖/路径穿越/版本拒绝。Leaf 26.3 隔离启动验证大厅世界迁移和加载。真实玩家跨区域传送、Canvas 世界加载/卸载、玩家死亡/重连、MythicMobs 实际刷怪仍需目标服务端流程验收。

本 MVP 尚未实现随机词缀、随机房间生成、复杂剧情、排行榜、GUI 编辑器，以及 Quest/自定义物品/经济/Worlds 产品适配器。rooms.yml 若出现会明确拒绝，不会忽略配置冒充随机生成。WorldProvider 是可用的 Bukkit ServicesManager 扩展契约，第三方适配器须在本插件初始化前注册并证明自身世界生命周期安全。

大厅为无怪物的集结与传送地图：敌对生物生成事件会在大厅内被取消；战斗副本配置不得引用大厅世界（包含热重载校验）。MM 守卫只属于 example 战斗副本。前往大厅只需 `/dungeon lobby`，无需执行 `/mm reload`。

## 石址配置目录

常驻刷怪地图的入口配置为 `plugins/Dungeon/dungeons/lost_altar/farming.yml`，具体配置说明见同目录 `README.md`。旧版根目录 `farming.yml` 在启动时校验并迁移，保留原文和注释；新旧配置同时存在时明确报错并保留文件。地图配置修改需完整重启，`/dungeon reload` 仅用于阶段副本。MythicMobs 的怪物、技能和掉落仍由其自身目录加载；旧固定刷怪点已停用，由 Dungeon 按 `spawning.yml` 在玩家附近随机刷新。`elite-hud.yml` 控制精英提示，Xerr 布局位于 `plugins/Xerr/core/layout/dungeon_elite.yml`。无固定全图怪物上限，默认每玩家 6 只、附近 10 只，远离玩家后自动回收。


副本默认死亡保留物品和经验（`keep-inventory: true`），阶段副本在 `dungeon.yml`、常驻刷怪地图在 `farming.yml` 配置。该选项与 `death-fails` 独立，不改变副本外世界规则。石址入口为 `181.771, 8, 11.174`，朝向 `-173.7`，俯仰 `-7.2`。

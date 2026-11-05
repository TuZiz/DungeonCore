# 石址（lost_altar）

`farming.yml` 控制常驻共享地图的启用、世界名和出生点。修改后完整重启服务器；`/dungeon reload` 只重载阶段副本，不重载此地图配置。

进入：`/dungeon enter lost_altar`。离开：`/dungeon exit`，返回大厅。

默认死亡不掉落物品与经验；`farming.yml` 的 `keep-inventory: false` 可恢复原有死亡掉落规则。其他阶段副本在 `dungeon.yml` 使用同名选项，未配置时也默认开启；不影响 `death-fails` 的失败判定，不修改副本外世界的游戏规则。

此地图没有队伍独立进度、阶段、限时或通关奖励；掉落由 MythicMobs 管理。Dungeon 通过 `spawning.yml` 在玩家附近随机刷新：

- 每 5 秒尝试 2 个位置，距离玩家 16～32 格，只使用已加载区块和可站立地面。
- 每玩家最多 6 只（其中精英最多 1 只）；32 格附近最多 10 只（其中精英最多 2 只）。没有固定全图上限，分散玩家独立刷新。
- 出生点 12 格内不刷新，距离所有玩家超过 80 格持续 10 秒后回收；旁观者不触发刷新。
- `elite-hud.yml` 配置精英提示距离，文字在 `plugins/Dungeon/lang/elite_zh_CN.yml`；Xerr 布局位于 `plugins/Xerr/core/layout/dungeon_elite.yml`，默认顶部居中。只显示最近的存活精英，需客户端成功加载资源包。
- 刷怪与精英提示配置修改后完整重启。区域调度器负责安全落点检查/生成，实体调度器负责位置采样/回收和玩家 HUD。

以下路径相对于服务器根目录，必须保留在 MythicMobs 目录中才能加载：

- 怪物：`plugins/MythicMobs/mobs/遗落圣坛.yml`
- 技能：`plugins/MythicMobs/skills/遗落圣坛.yml`
- 掉落：`plugins/MythicMobs/droptables/遗落圣坛.yml`

旧 `plugins/MythicMobs/spawners/Altar_01.yml` 至 `Altar_14.yml` 应移到服务器外备份；重启后不再使用固定 MM 刷怪点。请勿同时启用两套刷新。

首次安装 Xerr 布局后执行 `xerr reload`、`xerr pack`；等待生成结束，再执行 `ce reload pack` 并让客户端加载新包。缺少 Xerr 时仅停用提示，刷怪继续工作；缺少 MM 时暂停刷怪并记录日志。

地图归档保留在 `plugins/Dungeon/maps/lost_altar/`，永久地图标记保留在 `plugins/Dungeon/farming/`。这两个目录不是玩法配置。

刷新高度：以刷新时最新玩家快照为准，怪物脚部最多高于玩家 2 格、低于玩家 6 格（`spawning.yml` 的 `height.above/below`）。无安全地面时跳过本次刷新。

玩家击杀四种普通怪物和三种精英怪物均必掉 1–3 个铜币；精英另有 10% 概率额外掉 1 个银币。保留原材料掉落；数量和概率位于 MythicMobs 的 `droptables/遗落圣坛.yml`。

杀手兔（`AltarKillerRabbit`）：精英，60 生命、6 基础伤害，权重 5，使用原版杀手兔形态及攻击行为，沿用精英掉落和 HUD 指引。

精英提示上方中央显示20 GUI 像素高的原版指南针图标（32 方向帧，每 2 tick 按玩家相对目标方向更新）。Xerr 素材位于 `core/assets/image/dungeon/navigation/`，布局为 `core/layout/dungeon_elite.yml`。资源更新顺序：`xerr reload` → `xerr pack`（等待成功）→ `ce reload pack`（等待客户端加载）。

常驻地牢内死亡后，点击复活会回到该地图配置的出生点：石址使用 `farming.yml` 的 `spawn`（包括朝向），阶段常驻副本使用 `locations.yml` 的 `spawn`。不修改床/重生锚的个人记录，不影响地牢外、临时副本、剧情副本或末地传送门。死亡失败规则仍生效，死亡玩家不再被失败后的返回入口流程送出常驻地图。

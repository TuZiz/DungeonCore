# 石址（lost_altar）

`farming.yml` 控制常驻共享地图的启用、世界名和出生点。修改后完整重启服务器；`/dungeon reload` 只重载阶段副本，不重载此地图配置。

进入：`/dungeon enter lost_altar`。离开：`/dungeon exit`，返回大厅。

默认死亡不掉落物品与经验；`farming.yml` 的 `keep-inventory: false` 可恢复原有死亡掉落规则。其他阶段副本在 `dungeon.yml` 使用同名选项，未配置时也默认开启；不影响 `death-fails` 的失败判定，不修改副本外世界的游戏规则。

此地图没有队伍独立进度、阶段、限时或通关奖励；掉落由 MythicMobs 管理。Dungeon 通过 `spawning.yml` 在玩家附近随机刷新：

- 每 2.5 秒尝试 3 个位置，距离玩家 12～24 格，只使用已加载区块和可站立地面。
- 每玩家最多 10 只（其中精英最多 1 只）；24 格附近最多 16 只（其中精英最多 2 只）。没有固定全图上限，分散玩家独立刷新。
- 指定矩形安全区内不刷新，距离所有玩家超过 80 格持续 10 秒后回收；旁观者不触发刷新。
- `elite-hud.yml` 配置精英提示距离，文字在 `plugins/Dungeon/lang/zh_CN.yml`；Xerr 布局位于 `plugins/Xerr/core/layout/dungeon_elite.yml`，默认顶部居中。只显示最近的存活精英，需客户端成功加载资源包。
- 刷怪与精英提示配置修改后完整重启。区域调度器负责安全落点检查/生成，实体调度器负责位置采样/回收和玩家 HUD。

以下路径相对于服务器根目录，必须保留在 MythicMobs 目录中才能加载：

- 怪物：`plugins/MythicMobs/mobs/遗落圣坛.yml`
- 技能：`plugins/MythicMobs/skills/遗落圣坛.yml`
- 掉落：`plugins/MythicMobs/droptables/遗落圣坛.yml`

旧 `plugins/MythicMobs/spawners/Altar_01.yml` 至 `Altar_14.yml` 应移到服务器外备份；重启后不再使用固定 MM 刷怪点。请勿同时启用两套刷新。

首次安装 Xerr 布局后执行 `xerr reload`、`xerr pack`；等待生成结束，再执行 `ce reload pack` 并让客户端加载新包。缺少 Xerr 时仅停用提示，刷怪继续工作；缺少 MM 时暂停刷怪并记录日志。

地图归档保留在 `plugins/Dungeon/maps/lost_altar/`，永久地图标记保留在 `plugins/Dungeon/farming/`。这两个目录不是玩法配置。

刷新高度：以刷新时最新玩家快照为准，怪物脚部最多高于玩家 2 格、低于玩家 6 格（`spawning.yml` 的 `height.above/below`）。无安全地面时跳过本次刷新。

玩家击杀四种普通怪物和三种精英怪物均有 80% 概率掉落 1–3 个铜币；精英另有 10% 概率额外掉 1 个银币。保留原材料掉落；数量和概率位于 MythicMobs 的 `droptables/遗落圣坛.yml`。

杀手兔（`杀手兔`）：精英，60 生命、6 基础伤害，权重 5，使用原版杀手兔形态及攻击行为，沿用精英掉落和 HUD 指引。

精英提示上方中央显示20 GUI 像素高的原版指南针图标（32 方向帧，每 2 tick 按玩家相对目标方向更新）。Xerr 素材位于 `core/assets/image/dungeon/navigation/`，布局为 `core/layout/dungeon_elite.yml`。资源更新顺序：`xerr reload` → `xerr pack`（等待成功）→ `ce reload pack`（等待客户端加载）。

常驻地牢内死亡后，点击复活会回到该地图配置的出生点：石址使用 `farming.yml` 的 `spawn`（包括朝向），阶段常驻副本使用 `locations.yml` 的 `spawn`。不修改床/重生锚的个人记录，不影响地牢外、临时副本、剧情副本或末地传送门。死亡失败规则仍生效，死亡玩家不再被失败后的返回入口流程送出常驻地图。


安全出生与重生点：`-130.406, 7, 177.232`（yaw `127.3`，pitch `-3.2`）。矩形安全区包含方块 X `-164～-124`、Z `155～186`、Y `0～24`；高度覆盖入口地面（主要 Y=6）和净空，未沿用选择工具的临时高度。安全区内禁刷并免伤，出入有通知；区外正常战斗。由 Dungeon 的 `spawning.yml` 统一控制范围，无需 WorldGuard。DreamSkyblock 已让出 Dungeon 标记的死亡重生，两个 JAR 都需完整重启后生效。

## 上线保护

安全区内禁止造成伤害和发射攻击性投射物；末影珍珠与钓鱼通行工具不受此发射限制。地图禁止桶、点火、耕地破坏、容器存取和装饰实体修改，按钮与门、插件菜单仍可使用。

Dungeon 自定义 MM 掉落由击杀归属玩家优先拾取 30 秒，到期开放；期间禁止他人、生物、漏斗拾取及物品合并。普通玩家丢弃物不受影响。`drop-protection.yml` 的 `owner-seconds` 可设置 0–300 秒，修改后完整重启。仅使用 MM 给出的击杀归属，不按伤害贡献或队伍分配。

石址固定正午（6000 tick），关闭时间流逝并禁止跳时。怪物不能走入或传送进安全区；已在安全区的遗留怪物移动、重新加载或被 Dungeon 定期检查时直接清除，不产生击杀奖励。玩家在安全区不能造成近战或远程伤害。更新 JAR 后完整重启生效，无需修改已有配置。

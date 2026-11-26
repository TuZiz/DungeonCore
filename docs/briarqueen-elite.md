# 石址稀有精英：荆棘妖花

使用用户提供的 `briarqueen-rose.bbmodel`，作为玩家附近随机刷新的原地伏击精英。不是额外公共首领，不设置固定刷新点。

| 参数 | 默认值 |
| --- | --- |
| MM 内部 ID | 荆棘妖花 |
| 生命 / 护甲 | 240 / 3，位于独立 MM 定义中 |
| 刷新权重 | 2，现有其他精英各为 5；实际出现还受精英数量和落点检查约束 |
| 数量限制 | 共用每玩家 1 只、附近 2 只的现有精英上限，不提高总体刷怪频率 |
| 站位 | 原地生根，模型约 2.25 格高，要求 3 格纵向净空 |
| 毒雾 | 8 格，嘶鸣预警 22 tick；4 点基础伤害、中毒 I 60 tick、缓慢 I 40 tick，冷却 180 tick |
| 藤鞭 | 5.5 格，预警 14 tick；10 点基础伤害，冷却 100 tick |
| 突咬 | 3.2 格，预警 12 tick；8 点基础伤害，冷却 50 tick |

伤害值为护甲等减伤前基础值。按优先顺序选择距离内且冷却结束的招式，技能间有停顿；出生嘶鸣结束后才开始攻击。受击玩家的实际位置、模式、权限和安全区在其自身调度器重新检查；攻击不穿过实体方块。被取消、格挡或未实际扣血的攻击不会附加中毒、缓慢。

Folia 下射线涉及未归属该玩家区域或未加载区块时，保守跳过该次攻击，避免越区读取方块。实体和动画在实体调度器，死亡模型在位置所属区域，数据库或文件工作不进入战斗线程。本次仍不宣称已完成 Folia/Canvas/Arbor 实机验收。

掉落直接复用 `AltarEliteDrops`：铜币 80% 掉落 1–3 个，银币独立 10%，材料与现有精英一致；不参与石址守望者的幸运方块个人抽奖。Xerr 方向 HUD 根据 `elite: true` 自动识别；MobBars 需将内部 ID 加入 `xerr.elite-ids`，并为该 ID 设置 `height-offset: 0.50`。

## 配置与资源

- `plugins/Dungeon/dungeons/lost_altar/spawning.yml`：刷新条目，`model: 'briarqueen_rose'`、`headroom: 3`。
- `plugins/Dungeon/dungeons/lost_altar/elites.yml`：启用、模型、动画、攻击范围、伤害及冷却，schema-version=1。
- `plugins/MythicMobs/mobs/DungeonBriarqueenRose.yml`：血量、护甲及既有精英掉落表。
- `plugins/BetterModel/models/dungeon/briarqueen_rose.bbmodel`：BetterModel 原生模型。

缺少 BetterModel、模型、MM 定义或模型精英配置错误时，只跳过模型精英，保留普通怪及原有精英。正常死亡释放刷新名额，完整播放枯萎动画后回收；远离玩家、安全区回收、依赖停用和插件关闭会清理战斗任务与模型。

模型和技能配置在启动时读取；此次不扩展已有阶段副本重载命令。更新后正常重启，再让 CraftEngine 重新构建和分发合并了 BetterModel 的整包。不要用测试服 `build.zip` 替换正式服整包。

## 模型来源及验证边界

通过真实 Blockbench MCP 导入并导出用户模型；52 个立方体、17 根骨骼、128×128 原纹理及 idle/hiss/lunge_bite/whip_crack/wilt 五个原动画保留，下载原文件不修改。仅将 wilt 复制为 death 别名以供标准模型工具使用，战斗死亡仍播放原始 wilt。

Blockbench 造型预览已目视检查；原模型与导出模型的立方体 UUID、坐标、旋转、UV、内嵌纹理和原动画对比通过（浮点容差 1e-8）。实际 BetterModel 3.6.0-SNAPSHOT-534 生成 32 个子模型，合计 52 个立方体完整保留。

Maven 编译和 44 项现有测试通过。隔离 Leaf/Purpur 26.3、Java 25、MM 5.14、BetterModel 3.6、CE 26.9.2 的 RoseProbe/BossProbe 通过：真实附近刷新、240 生命与 3 护甲、骨骼和动画、玩家代理自然预警及伤害/中毒/缓慢、墙体阻挡、安全区免伤、死亡模型和刷新名额释放，以及原 Boss 回归。记录见 `target/coin-smoke263/boss-probe-result.json`。隔离服通过插件区块票据维持无真实玩家测试位置的实体计时，不给生产刷怪增加常驻区块票据。

Player 代理不等同于网络玩家。客户端材质包观感、模型边缘命中箱、帧率和多人实战仍需进服确认。底层实体为尸壳，命中箱不会逐块包住花瓣和藤鞭。

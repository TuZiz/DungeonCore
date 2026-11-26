# 荆棘妖花 · 有效资产记录

- 用户要求：基于 `C:/Users/32633/Downloads/briarqueen-rose.bbmodel`，新增一只较难打的精英怪。
- 唯一造型来源为用户模型，保留 52 个立方体、17 根骨骼、128×128 内嵌纹理和原有 5 个动画；不重画纹理，不修改下载原文件。
- 通过真实 Blockbench MCP 导入、检查和原生导出；补充 death 动画别名以对接 BetterModel，内容来自原有 wilt 动画。
- 玩法定位：常驻石址的稀有原地伏击精英，突咬、藤鞭及毒雾，不使用固定刷怪点。
- 运行模型：`src/main/resources/elites/models/briarqueen_rose.bbmodel`。
- 预览：`assets/briarqueen-rose/preview.png`，用于造型检查，不代表 Minecraft 实际显示。
- 当前状态：MCP 导入导出、预览目视检查、原模型几何/UV/纹理/原动画对比均已完成；差异检查使用 1e-8 浮点容差，不修改造型。
- BetterModel 3.6.0-SNAPSHOT-534 实际生成 32 个子模型，合计 52 个立方体完整保留。
- 隔离 Leaf/Purpur 26.3 + Java 25 的 RoseProbe/BossProbe 已通过：模型挂载、动画、玩家代理技能伤害与安全区边界、死亡枯萎回收。尚未网络客户端、Folia 实机及多人实战验收。

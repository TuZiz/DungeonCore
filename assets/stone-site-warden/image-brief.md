# 石址守望者 · 有效资产记录

- 用户要求：Blockbench MCP 建模，BetterModel 加载外观、攻击动作与特效。
- 最新用户要求：不再自建首领外观，基于 `C:/Users/32633/Downloads/runestone-warden-golem.bbmodel`。该文件是唯一生效的首领造型来源。
- 造型、297 个立方体、256×256 内嵌纹理及 6 个现有动画均保留；原下载文件不修改。
- 资产：`stone_site_warden.bbmodel`（首领），`stone_site_wave.bbmodel`（地面预警与冲击波）。
- 制作方式：用 Blockbench MCP 导入用户模型及补充关键帧；不重画用户纹理。此前自建版本已淘汰，不部署。
- 共享色板：灰绿石材、深色接缝、苔绿边缘、古铜包边、青绿符文；攻击范围使用琥珀预警。
- 用户原动画：idle、walk、attack_1、attack_2、special、death。补充出生、受击动作；特效 telegraph、burst。
- 输出：`src/main/resources/boss/models/`，可编辑源文件与运行文件相同，纹理内嵌。
- 预览：`assets/stone-site-warden/preview.png`；必须实际目视检查后更新状态。
- 当前状态：已通过 Blockbench MCP 导入、补齐出生和受击动画并导出；`preview.png` 已更新为用户模型且目视检查通过。模型的 297 个立方体 UUID、旋转、UV 与原文件一致，仅两个无旋转立方体的原点末位浮点数被 Blockbench 正常舍入。
- 已由 BetterModel 3.6.0-SNAPSHOT-534 在隔离 26.3 服务端解析并生成资源包：68 个子模型、297 个立方体完整保留。最终 JAR 已通过真实模型挂载、动画管线/效果、移动物理、受伤死亡、票据与视觉清理、依赖停止时取消迟到召唤的检查。未进行客户端游戏显示、真实玩家命中/库存联测、FPS 或 Folia 实机验收。
- 兼容边界：原文件为 Generic/free，由 BetterModel 转换；24 个多轴旋转块被转换，不能直接当原版实体模型安装。当前铁傀儡碰撞箱不逐块覆盖肩甲/藤蔓，青色贴图不承诺自发光。详细记录在 `docs/stone-site-boss.md`。

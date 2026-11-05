# Dungeon 架构与开发计划

基线：Kotlin、Maven、Java 21、Paper API 1.21.11。Folia/Canvas/Arbor 通过区域调度 API 运行；不把构建通过当作服务端实测。

## 分层
- model：不可变定义、串行会话状态机，禁止 Bukkit 依赖。
- configuration：独立副本目录，加载全部文件后校验跨文件引用，原子发布新快照；运行实例固定旧快照。
- scheduling：global 管世界注册表；region 管坐标处实体/方块；entity 管玩家、消息、背包；IO 使用独立受控执行器。
- world：WorldProvider 隔离动态加载能力；模板异步复制，专属标记保护，卸载确认后异步删除。
- persistence：SQLite 单线程数据库执行器、事务、奖励唯一键。奖励先持久化领取意图，避免崩溃重发；领取中断保留人工审计记录，不假称外部奖励 exactly-once。
- session：实例与成员身份、阶段计数、暂停重连、超时回收。
- integration：可选依赖独立适配器，缺失时拒绝使用相关配置而不影响其他副本。

## 阶段与验收
1. 基础：状态机、配置引用校验、SQLite 事务与并发测试。
2. 玩法：常驻实例、队伍绑定、击杀/互动/探索/收集、机关、奖励；逐次编译。
3. 世界：模板复制、实例 ID、卸载回调、保护性清理、启动残留恢复；文件安全测试。
4. 管理与扩展：命令、单副本重载、诊断、MythicMobs、受限事件条件动作注册机制；打包检查与文档。

## 平台边界
Canvas 1.21.11 官方世界 API：https://docs.canvasmc.io/canvas/developers/api/world-loading/
创建必须 global；卸载使用 unloadWorldAsync(World, boolean, Consumer)，回调后复核注册表才允许删除。
Folia：https://github.com/PaperMC/Folia 。没有已验证动态世界能力时只允许已加载常驻地图。
Arbor：https://github.com/ArborTeam/Arbor 。未提供精确构建，不假设 Luminol API；默认仅已加载地图。后续可通过 Bukkit ServicesManager 注册 WorldProvider 接入已验证 Worlds/Arbor 适配器。

## 明确延后
随机生成 rooms.yml、复杂剧情语言、排行榜、Quest/自定义物品/经济与 Worlds 的具体产品适配器需确定产品和版本。不创建空实现冒充支持。未知配置应校验报错。

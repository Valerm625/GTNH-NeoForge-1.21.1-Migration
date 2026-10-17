# GTNH 迁移暂停交接 — 2026-10-17

本阶段已完成并上传 GitHub，按用户要求暂停后续开发。

- location 任务：34 个旧任务、33 个节点，全部是 dimension-only 且无限范围；已加入只对 `gtnh_legacy_dimension_only` 标签生效的 LocationTask Mixin。
- 适配范围：旧维度 `-1/0/1` 映射到 `minecraft:the_nether`、`minecraft:overworld`、`minecraft:the_end`；其他维度不猜测。普通 FTB 坐标盒任务不受影响。
- 最新任务候选：47 个结构候选、2 个 crafting 候选、2 个 choice 候选、3 个 hunt 候选；实际依赖闭合导出仍为 2 个。
- 完整重建、编译适配器形状检查、任务 SNBT 解析和源一致性验证通过；EULA 未接受，未声称游戏运行完成。
- 最新公开检查点以 GitHub `GTNH-NeoForge-1.21.1-Migration` 的最新提交为准。

恢复前先检查本地工作区和 GitHub 检查点，不要自动接受 EULA，不要把静态证据当成完整游戏验证。

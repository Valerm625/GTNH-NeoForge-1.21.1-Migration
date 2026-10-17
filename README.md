# GTNH → NeoForge 1.21.1 迁移检查点（未完成）

这是非官方迁移工作的**源码与进度检查点**，不是可直接安装游玩的整合包，不代表 GTNH、GregTech CEu、NeoForge 或 FTB 官方支持。

截至 2026-10-17，本阶段完成了显式旧版原版物品身份翻译：对照实际 1.21.1 `Items` 类的 1333 个字段，新增 241 个身份、覆盖 4905 次来源出现；累计 720 / 8388 个身份有定义映射，仍有 7668 个未映射。结构上可转换任务候选为 47 个，其中 2 个包含受限 crafting 任务；但依赖闭合的实导出仍为 2 个，3812 个任务未导出。

## 本阶段证据

- `work/migration/legacy_vanilla_items.py`：显式同名、重命名和有限 metadata 映射；拒绝模糊匹配。
- `evidence/vanilla-identity-mapping.json`：现代字段数量、源证据哈希、冲突数量和负例边界。
- `evidence/vanilla-modern-Items.javap.txt`：本地锁定 Minecraft 1.21.1 server `Items` 类的文本字段证据，不包含游戏二进制。
- `evidence/phase-2026-10-17-vanilla-identity.json`：身份映射阶段前后覆盖率与测试边界。
- `evidence/phase-2026-10-17-crafting-subset.json`、`evidence/crafting-task-adapter.json`：受限 crafting 任务候选的源码约束和验证边界。
- `evidence/phase-2026-10-17-choice-reward.json`、`evidence/choice-reward-adapter.json`：受限 item choice 奖励候选的源码约束和验证边界。
- `evidence/phase-2026-10-17-hunt-subset.json`、`evidence/hunt-task-adapter.json`：受限实体 hunt/kill 候选的源码约束和验证边界。
- `evidence/phase-2026-10-17-location-boundary.json`、`evidence/location-task-boundary.json`：location 任务无法安全转换的源码证据和阻塞边界。

本阶段仍未完成完整任务迁移或游戏内验证。

## 全局边界

目标仍是完整功能迁移，不是当前检查点的子集。未上传原始 ZIP、Minecraft/模组/JDK 二进制、第三方完整源码、纹理/声音、原任务正文、存档或凭据。没有完整世界启动、实际 Mixin 应用、完整任务初始化或客户端渲染证据；EULA 保持 false。

恢复原工作区前先读 `RESUME.md`。本阶段完成后按用户要求暂停开发；恢复后必须重新运行真实验证，而不能把本仓库的文本证据当作完整游戏验证。

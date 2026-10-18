# 独立 UUID 进度账本与文件恢复

Phase 4 新增 `LegacyQuestProgressStoreCodec` 和 `LegacyQuestProgressFileStore`。它们将已解释的逐玩家进度、原 quest UUID/task index/factory 和原始导入字节保存为独立 NBT 文件。现已验证实际文件读写和独立 JVM 中断后的重新加载；没有注册世界生命周期、调用 FTB 完成/奖励或修改真实玩家库存，任务覆盖率不因此增加。

## 存储身份与原件

外层 schema 为 1，包含 Long revision、前帧 SHA256、定义文件摘要、物品身份映射摘要、定义目录 shape 摘要、来源字节档案和完整状态。定义目录摘要由排序后的全部 quest UUID、原 task index、factory 与 requirement count 计算；不能只传一个相同版本字符串后改变需求 shape。绑定摘要由调用者提供，必须来自真正验证的定义及映射。测试中的身份映射摘要明确代表来源符号夹具，不证明 `logWood` 的现代成员集合。

任务状态仍按完整身份寻址，保留全部 UUID 行和独立 completeUsers，包括离线 UUID、只有完成标记而没有计数行的 UUID。quest completion 除路由 UUID 外保持不透明，完整保留 compound 字段和类型。文件格式读取不采用旧导入器的截断/补零/重复覆盖：自己的已保存状态若有错误行长度、重复玩家、重复任务、重复 quest 或未知字段，整份拒绝，不能悄悄修复为另一份状态。

来源条目存 identity、SHA256 和完整 byte array；identity 只作描述，不作为路径。字节可以是未知或损坏的 JSON，归档并不等于成功解释或应用。每个后继 snapshot 必须保持此前来源列表为精确前缀，不可删除、改写或重新排序；同名来源的新内容可追加。来源名称加内容摘要相同的重复条目拒绝。原 BQ 多文件加载顺序仍需另行证明，账本不会自行猜测该顺序。

`read` 返回候选或问题及原 NBT 深复制；未知版本、错误类型、缺字段、来源摘要变化、定义/映射/目录不符都拒绝。原始文件始终由文件层保留。单帧上限 64 MiB，读取 NBT 内存预算 256 MiB；超限明确停止，绝不截断原件。保留所有历史帧会增长磁盘空间，压缩/去重/安全整理尚未实现。

## 文件发布与恢复

`open(directory, binding, catalog)` 取得 `ledger.lock` 的进程排他锁。目录仅接受普通文件：锁、已发布帧和规定形式的 pending 临时文件。非普通文件和未知文件阻止加载。世界接入时必须从同一服务端的 overworld 路径取得唯一实例；当前尚未注册该接入。

已发布文件名为 `20位revision-SHA256.nbt`。每次读取检查所有帧的文件摘要、NBT 类型、版本绑定、连续 revision、parent 摘要和来源前缀。任何损坏、缺帧或分叉都阻止读取，不能退回最后一个“看起来正常”的进度后继续写。调用者不得捕获该错误后新建空账本；正确处理是关闭本次操作并保留目录供恢复。

一次 commit 要提供预期前帧摘要。请求过期则在写入前拒绝；新快照先编码并验证 NBT 二进制往返，然后创建同目录唯一临时文件、写入并 `FileChannel.force(true)`，最后要求 `ATOMIC_MOVE` 发布到新 revision 文件。文件系统不支持原子移动时直接失败，无普通覆盖回退。已发布文件不覆盖、不删除。

异常可能发生在发布之后，因此发生写入阶段异常的实例被标记为结果未确定，必须 close/open 后重新读头，不能盲目重试。发布前中断留下 pending 原件并恢复旧头；发布后中断恢复新头。所有旧帧保持不变。该协议只保护这一份账本，**不使玩家库存与进度成为同一事务**。

进程终止与机器掉电不同：没有验证目录项在掉电时的持久性；外部手工删除完整尾部也无法仅靠剩余哈希链被发现。当前锁协议针对遵守同一锁的写者，不是防恶意篡改机制。未来跨文件恢复还必须加入玩家库存持久化水位及已验证恢复动作；不能将“重新加载不会重复执行纯规划器”说成真实库存恰好扣除一次。

## 对真实 NeoForge 存储 API 的核验

核验安装的 `neoforge-21.1.262-server.jar`，而非仅看未打补丁的 Minecraft 类：

- NeoForge 的 `DimensionDataStorage.readTagFromDisk` 在 DataFixTypes 为 null 时跳过 data fixer；未打补丁 MC 没有该分支。因此不能把基础 MC 的 null 行为推及 NeoForge。
- `readSavedData` 仍捕获 Exception 并返回 null，`computeIfAbsent` 随后调用新建 supplier。直接在 deserializer 中抛异常并不足以防止损坏进度被当成缺失进度。
- NeoForge `SavedData.save(File, Provider)` 将 NBT 副本交给 `IOUtilities.withIOWorker`，写入使用 `writeNbtCompressed`；它不是基础 MC 的直接同步写入。该 API 返回不能直接用作迁移事务已经持久化的确认，也不能把两份文件变成一个事务。

本次字节码人工复核记录和安装 JAR/反编译文本摘要保存在 `evidence/legacy-progress-storage-api-audit.json`。这是代码审查证据，不是实际世界 IO 测试。可用锁定 JDK 的 `javap -c -p -classpath <neoforge-21.1.262-server.jar> <类名>` 复核上述两个类。

因此当前采用显式失败、同步返回且保留全部帧的独立文件层。后续可增加安全的 SavedData/服务器生命周期适配，但不能绕过加载错误或用 markDirty 当作跨文件提交证明。

## 本阶段验证

`test_legacy_quest_progress_store.py` 使用真实 1.21.1 NbtIo 与本机文件系统，通过 **436 项断言、25 个格式拒绝变体、64 次 NBT 二进制往返**，并启动独立 JVM 检查第二写者锁冲突。三个 JVM 在 `TEMP_OPENED`、`TEMP_FORCED`、`PUBLISHED` 处以 exit 71 立即终止，再用另一个 JVM 验证恢复旧/新 revision，原件和旧帧均保留。还覆盖损坏帧、断链、分叉、未知文件、未来 schema、尾随字节、配置变化、原件删除/重排及发布后异常。

组合测试现在有 **27 项断言**：真实主线 `0:2` 定义 → synthetic typed progress → envelope → 独立账本保存/关闭/重开 → planner → 计划状态保存/重开 → 再次规划。5 logs 先检测再计算交付 2，剩 3；重新读取后的任务 latch 阻止模型重复交付。原件保持两条精确字节记录。这里的库存、授权、参与者和物品谓词仍是夹具，没有写入真实玩家数据，也没有新增原游戏 oracle。

报告为 `evidence/quests/LegacyQuestProgressStoreCheck.json` 及 `LegacyProgressImportPipelineCheck.json`。完整构建另核验测试类与兼容 JAR 的生产类字节一致。真实玩家存档 fixture 仍为 0，游戏、联网、奖励和全功能迁移仍未完成。

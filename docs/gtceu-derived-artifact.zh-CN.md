# GTCEu 派生产物的来源、安装与恢复

基础包及 `work/neoforge-smoke` 使用 `gtceu-1.21.1-7.0.2-gtnh-kubejs72.jar`。它来自已验证的局部源码补丁，功能与限制见 [KubeJS桥接](gtceu-kubejs72-bridge.zh-CN.md)。安装本身不代表完整GTNH、真实机器运行或客户端视觉已经验收。

## 原始与派生身份

- `locks/artifacts.lock.json` 保留原始下载URL和摘要。
- 原始JAR缓存位于 `work/cache/original-artifacts/c09a550523e931342a575193cbaad2e4b643e5de2cefe280cd1de540022c6388/gtceu-1.21.1-7.0.2.jar`，同时按旧下载锁与SHA256校验。
- `locks/gtceu-derived-artifact.json` 固定派生摘要 `57756c966739f22aee0d8eccfe061e39f36c1d9cac60b819ea74b10f7a55b239`、原始身份、源码提交、补丁、各重编译类及源文件摘要。
- 派生JAR内的来源元数据须与锁一致；25个重编译类按锁检查，其余14084个原JAR条目与原件逐字节比较，包括全部资源和嵌套依赖。JAR新增、缺失、重复或未知修改均拒绝。
- 每个实例的 `docs/gtceu-derived-artifact.json` 记录本地构建身份，并进入运行时文件清单。它不会记录机器绝对路径或冒充上游下载元数据。

既有GTCEu客户端隔离与LDLib回调锁不放宽。构建兼容模组前以及两侧原生检查中，相关原始目标类和嵌套LDLib继续按旧锁验证。派生模组的显示版本仍为7.0.2，实际文件与来源锁明确区分。

## 安装及中断处理

`install_gtceu_bridge.py` 先保存并校验原始缓存，再从固定源码和依赖重新构建。仅在结果与派生锁一致时写入实例；旧原件删除前再次验证其摘要及缓存。配置、EULA、其他模组和未知修改均保留。

新JAR先写入独立临时文件并校验，然后重命名；如在移除旧JAR或写入记录前中断，加载前检查会拒绝双JAR或缺失记录。再次运行安装器可以恢复这些已知状态。被改动的原JAR、派生JAR、缓存或补丁会停止安装并保留，不用“重新下载覆盖”掩盖问题。此流程验证进程中断状态，不宣称机器掉电时的文件系统持久化保证。

```powershell
python work/migration/install_gtceu_bridge.py
python work/migration/test_gtceu_artifact.py
python work/migration/rebuild_current_phase.py
```

18个检查使用独立的真实JAR副本，覆盖正常替换、重复执行、双JAR/缺失记录恢复、错误输入拒绝、已锁定前版升级保留和恢复计划。公开结果为 `evidence/GTCEuArtifactCheck.json`。

已锁定前版的升级另受 `previous_artifacts` 限制：只接受摘要和内嵌来源元数据都匹配的前版，先保存到 `work/cache/derived-artifacts/<sha256>/` 并读回校验，再原子替换。当前加载验证仍拒绝前版，未知改动仍拒绝安装；不会把任意同名 JAR 当作可覆盖对象。

## 从现有工作区恢复

`restore_local_checkpoint.py` 验证来源工作区两个实例的派生JAR、原始缓存、安装记录及其余上游模组。复制时保留原始缓存与构建输入，但跳过活动派生JAR、安装记录和派生构建目录。恢复后必须执行完整重建；第一轮安装可以直接从缓存原件构建，不依赖mods目录内已有GT。

恢复计划、模组身份及完整本地输入预检均已执行；[预检结果](../evidence/local-restore-input-check.json)核验五份固定源码树、2036个运行输入、9358个主线源文件和11977个旧包参考文件。原始输入树仍按历史清单核验，本阶段未进行第二份完整大工作区复制。旧本地测试结果仍视作历史，不能代替恢复后重建与检查。普通启动脚本保持EULA检查，同时拒绝与已验证清单不符的多余或缺失模组。

完整迁移、任务提交/奖励、客户端视觉与阶段门槛仍需继续实现和验证。noShrink格子行为已补齐固定源对照，真实工作台仍待验收，见[有序合成定位](legacy-shaped-geometry.zh-CN.md)。

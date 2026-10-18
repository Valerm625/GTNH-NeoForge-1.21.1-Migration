# 用户提供的 GTCEu 重构仓库：接入评估

固定来源为 [GregTech-Modern-Unofficial-Rebuild 的 0c63302](https://github.com/Valerm625/GregTech-Modern-Unofficial-Rebuild/tree/0c63302896851b340022d568b8211cebc603f1f1)。它是可修改的源码候选与接口参考，当前基础包仍使用经过 r27 验证的 GTCEu 7.0.2。此次评估没有安装候选模组、改变运行包或向候选仓库提交修改。

## 已查明的适配价值

候选版本为 `8.0.1-unofficial`，保留 `gtceu` 模组 ID，编译依赖为 NeoForge `21.1.234`、KubeJS `2101.7.2-build.336`、Rhino `2101.2.7-build.81`。现有基础包使用 NeoForge `21.1.262`、KubeJS `2101.7.2-build.377` 和 Rhino `2101.2.7-build.85`。

候选已使用 `RecipeScriptContext`、`RecipeComponentTypeRegistry` 等 KubeJS 7.2 接口，可用于核对当前配方桥接修复。它的通用 `GTKubeRecipe.output` 使用 `ALL_OUTPUTS` / `ALL_TICK_OUTPUTS`；7.0.2 相同方法错误地选择输入键。采用这一修复时仍需验证普通输出、每 tick 输出、数量、概率及序列化，不能把源码差异当成运行验收。

## 不能直接替换的已知原因

- 候选 `GTShapedRecipeSchema` 引用 `KubeJSCraftingRecipe.STAGE_KEY`。使用当前基础包的真实 KubeJS 377 和 JDK 21 编译该字段引用，得到 `cannot find symbol`；同类 `MIRROR_KEY` 正例编译成功。这是具体 API 差异，不代表整个候选构建已执行。
- 主要界面依赖变为 ModularUI `3.3.1-SNAPSHOT`，其仓库固定了时间戳产物摘要。现有兼容模组精确依赖 GTCEu 7.0.2 和 LDLib 1.0.35.a，不能仅替换模组文件。当前六个 GTCEu CoreMod 目标中，五个在候选树中没有相同源码路径，需要重新定位和审计；路径变化本身不证明功能删除。
- 候选主资源与生成资源合计 10,855 个 `assets/` 路径，与 7.0.2 原 JAR 的 10,657 个路径比较：9,219 个共同路径字节相同，1,160 个共同路径字节不同，278 个仅在旧 JAR，476 个仅在候选源码。逐项路径和摘要保留在审计 JSON 中。这是“旧产物与新源码”的比较，缺少同名路径可能是重命名或生成方式改变，字节不同也可能是编码差异；不能据此判断视觉退化或等价，更不能代替 GTNH 原始资源验收。
- 候选文档记录其恢复目标尚有 60 项非 class 资源差异待审查，并记录 `runData` 因 `block.gtceu.creosote` 重复键失败。这与本工程对 7.0.2 的资源比较是两个不同范围。

候选文档报告过 160/160 项 GameTest 通过，但同时说明普通 Gradle `test` 没有发现 JUnit 测试、完整客户端和整包迁移未验证。本工程没有重复运行这些 GameTest，也不将这个数字计入自己的验收结果。

## 接入顺序与复现

先参考候选的 7.2 接口改动，把当前配方桥接修复做成可复现补丁，并验证输入/输出、概率、条件、工具、燃料和资源重载。整体升级到 8.0.1 前，需另行编译固定源码与依赖，重新审计所有旧兼容补丁，核对已有 720 条身份映射及任务检测，并逐项处理资源差异。仍以用户要求的完整 GTNH 功能与贴图为目标，不因候选实现不同而删去旧行为。

锁文件：`locks/gtceu-rebuild-candidate.json`。证据：`evidence/gtceu-rebuild-candidate-audit.json`。已恢复现有基础包与 JDK 21 后，在本仓库根目录执行：

```powershell
git -c core.longpaths=true clone https://github.com/Valerm625/GregTech-Modern-Unofficial-Rebuild.git work/upstream/gt-modern-unofficial-rebuild
git -C work/upstream/gt-modern-unofficial-rebuild -c core.longpaths=true checkout --detach 0c63302896851b340022d568b8211cebc603f1f1
python work/migration/audit_gtceu_rebuild_candidate.py work/upstream/gt-modern-unofficial-rebuild
```

审计读取固定 Git 对象，不依赖 Windows 换行形式，不修改候选源码；资源路径冲突保留全部候选，不选择覆盖赢家。输出为来源与兼容性评估，`candidate_installed`、`candidate_build_executed`、`candidate_gametests_executed`、`full_parity` 均为 false。原有 45 份运行检查仍属于 r27 的已验证产物，本轮没有运行代码变化，因此没有重复整套运行检查。

本轮文本及审计脚本由 Codex 编写。第三方完整源码、资源和二进制均不随此源码检查点发布。EULA 与世界测试边界不变；依赖闭合任务导出仍为 2/3814。

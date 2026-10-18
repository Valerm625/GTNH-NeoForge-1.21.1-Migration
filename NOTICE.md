# 来源、许可和分发边界

本仓库是非官方、未完成的迁移研发检查点，不是原作者背书的产品。没有为整个仓库擅自指定统一的新许可证。

- `work/compat-mod/src/main/resources/LICENSE` 保留现有本地适配器 MIT 声明，只适用于对应适配器；下述旧 Forge 有序合成移植类除外。
- 转换和校验工具引用原模块名称、API 形状、版本、配置值与有限源码锚点；完整上游源码与资源没有上传。任何有版权的上游片段仍受其原许可约束，不能因为存放在本仓库而重新许可。
- CoreMod 2.9.84 来源为 GTNewHorizons/NewHorizonsCoreMod（GPLv3）；GT5U 5.09.54.205 为 GTNewHorizons/GT5-Unofficial（LGPLv3）；BetterQuesting 3.8.89-GTNH 为 GTNewHorizons/BetterQuesting（MIT）。来源提交和归档哈希见 `locks/sources/`。
- Railcraft、FTB 以及各现代模组遵循各自上游声明。没有把它们视作自动可再分发的内容。本仓库只保存公开版本/下载来源/哈希元数据，不包含它们的二进制、完整源码或资源。
- 输出运行输入哈希仅描述本地测试状态，不代表那些文件已随仓库提供，也不授予第三方文件使用权。
- `work/compat-mod/patches/gtceu-kubejs72.json` 是针对固定 GTCEu 7.0.2 源码的局部补丁，沿用上游 LGPL-3.0 及文件声明；来源归档和构建依赖见 `locks/gtceu-kubejs72-build.json`。它不受本地兼容模组的 MIT 声明重新许可。派生 JAR 与第三方完整源码仍只在本地生成，未随此仓库发布。
- `LegacyShapedRecipe.java` 的格子匹配移植自 Forge `10.13.4.1614` 的 `ShapedOreRecipe`，该文件及编译类沿用 Minecraft Forge Public Licence 1.0。固定来源见 `locks/legacy-shaped-source.json`；完整许可文本和归属说明随兼容模组的 [MinecraftForge-License.txt](work/compat-mod/src/main/resources/MinecraftForge-License.txt) 与 [NOTICE](work/compat-mod/src/main/resources/NOTICE) 保留。源码对照工具仅在本地从固定归档提取未修改原类，未把整个旧 Forge 源码或 MCP 数据加入仓库。

公开前排除了账户凭据、机器绝对路径、原包 ZIP、游戏/JDK/模组 JAR、原任务正文、纹理、音频与存档。此仓库公开不等于宣布整合包完成或许可审计完成；扩大分发范围前应逐项检查权利与归属。

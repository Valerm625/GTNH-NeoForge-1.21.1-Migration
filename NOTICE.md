# 来源、许可和分发边界

本仓库是非官方、未完成的迁移研发检查点，不是原作者背书的产品。没有为整个仓库擅自指定统一的新许可证。

- `work/compat-mod/src/main/resources/LICENSE` 保留现有本地适配器 MIT 声明，只适用于对应适配器。
- 转换和校验工具引用原模块名称、API 形状、版本、配置值与有限源码锚点；完整上游源码与资源没有上传。任何有版权的上游片段仍受其原许可约束，不能因为存放在本仓库而重新许可。
- CoreMod 2.9.84 来源为 GTNewHorizons/NewHorizonsCoreMod（GPLv3）；GT5U 5.09.54.205 为 GTNewHorizons/GT5-Unofficial（LGPLv3）；BetterQuesting 3.8.89-GTNH 为 GTNewHorizons/BetterQuesting（MIT）。来源提交和归档哈希见 `locks/sources/`。
- Railcraft、FTB 以及各现代模组遵循各自上游声明。没有把它们视作自动可再分发的内容。本仓库只保存公开版本/下载来源/哈希元数据，不包含它们的二进制、完整源码或资源。
- 输出运行输入哈希仅描述本地测试状态，不代表那些文件已随仓库提供，也不授予第三方文件使用权。

公开前排除了账户凭据、机器绝对路径、原包 ZIP、游戏/JDK/模组 JAR、原任务正文、纹理、音频与存档。此仓库公开不等于宣布整合包完成或许可审计完成；扩大分发范围前应逐项检查权利与归属。

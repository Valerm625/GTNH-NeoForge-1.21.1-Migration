from pathlib import Path
import json, hashlib, collections, zipfile
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'; REPORT=OUT/'docs/reference-2.8.4'
summary=json.loads((REPORT/'summary.json').read_text(encoding='utf-8'))
old=ROOT/'work/source-release-2.8.4/GT New Horizons 2.8.4/.minecraft'
new=ROOT/'work/source/GT-New-Horizons-Modpack-master'
def quests(root):
    data={}; conflicts=[]
    for p in sorted((root/'config/betterquesting/DefaultQuests/Quests').rglob('*.json')):
        d=json.loads(p.read_text(encoding='utf-8-sig'))
        k=f"{d['questIDHigh:4']}:{d['questIDLow:4']}"
        b=d.get('properties:10',{}).get('betterquesting:10',{})
        rec={'id':k,'path':p.relative_to(root).as_posix(),'name':b.get('name:8',''),'data':d}
        if k in data: conflicts.append(k)
        data[k]=rec
    return data,conflicts
a,ad=quests(old); b,bd=quests(new)
changes=[]; identical=0
for key in sorted(a.keys()&b.keys()):
    aa,bb=a[key],b[key]
    if aa['data']==bb['data']: identical+=1; continue
    task_changed=aa['data'].get('tasks:9')!=bb['data'].get('tasks:9')
    reward_changed=aa['data'].get('rewards:9')!=bb['data'].get('rewards:9')
    prerequisites_changed=aa['data'].get('preRequisites:9')!=bb['data'].get('preRequisites:9')
    changes.append({'id':key,'release_name':aa['name'],'master_name':bb['name'],'release_path':aa['path'],'master_path':bb['path'],'tasks_changed':task_changed,'rewards_changed':reward_changed,'prerequisites_changed':prerequisites_changed})
comparison={'identity_key':'signed questIDHigh:questIDLow, not filename','release_2_8_4_quests':len(a),'master_quests':len(b),'duplicate_ids_in_release':ad,'duplicate_ids_in_master':bd,'common_ids':len(a.keys()&b.keys()),'identical_quests':identical,'changed_common_quests':len(changes),'task_definition_changes':sum(x['tasks_changed'] for x in changes),'reward_definition_changes':sum(x['rewards_changed'] for x in changes),'prerequisite_changes':sum(x['prerequisites_changed'] for x in changes),'release_only':[{'id':k,'name':a[k]['name'],'path':a[k]['path']} for k in sorted(a.keys()-b.keys())],'master_only':[{'id':k,'name':b[k]['name'],'path':b[k]['path']} for k in sorted(b.keys()-a.keys())],'changed':changes,'note':'Structural changes are evidence for manual reconciliation, not proven gameplay differences. Localization and serialization can also differ.'}
(REPORT/'quest-id-comparison.json').write_text(json.dumps(comparison,ensure_ascii=False,indent=2),encoding='utf-8')
recipe_classes={}
for jar in sorted((old/'mods').glob('*.jar')):
    if not any(s in jar.name.lower() for s in ['gregtech','gtnewhorizonscore']): continue
    with zipfile.ZipFile(jar) as z:
        names=[n for n in z.namelist() if n.endswith('.class') and any(s in n.lower() for s in ['recipe','worldgen','material','machine','research'])]
        recipe_classes[jar.name]={'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'class_names':names,'count':len(names),'inspection':'ZIP filenames only; not decompiled or executed'}
(REPORT/'core-implementation-locations.json').write_text(json.dumps(recipe_classes,ensure_ascii=False,indent=2),encoding='utf-8')
text=f'''# 辅助来源接收报告：GT New Horizons 2.8.4

## 定位

这是独立的 **2.8.4 原版参考实例**，不是 1.21.1 移植成品。启动器清单明确指定 Minecraft **1.7.10**、Forge **10.13.4.1614**。文件名的 Java 17–25 指运行环境，不是 Minecraft 游戏版本。

原始 master ZIP 的 README 标注 **2.9.0-RC-2**；该主来源保持不变。新包用于补齐实际 JAR、原始材质、实现位置、任务和本地化参考，不会自动覆盖或混装到现代 mods/config。两者不同版本内容须逐项核对。

## 已验证内容

- 原包大小：{summary['archive_bytes']:,} 字节。
- SHA-256：`{summary['archive_sha256']}`。
- {summary['jar_count']} 个顶层 JAR：229 个位于原实例 mods，1 个位于 libraries；这不是注册 mod ID 数量。
- 所有 230 个 JAR 的 ZIP 条目 CRC 校验通过；此结果不代表安全扫描、功能正确或跨版本兼容。
- JAR 内索引到 {summary['jar_texture_pngs']:,} 个贴图 PNG 条目、{summary['asset_namespaces']} 个资源命名空间。尚未逐图解码或进行游戏渲染验证。
- {summary['configuration_files']:,} 个配置目录文件；没有独立 scripts 目录文件。核心 JAR 内已定位配方、材料、机器和世界生成相关类名，尚未移植或反编译。
- 4 组仅大小写不同的路径，内容逐字节一致：Windows 解包时保留一份，别名和哈希全部记录，原始 ZIP 未修改。
- 未执行新包中的脚本、JAR 或文档指令。

## 任务来源核对

按任务 ID（高/低两段）核对，而非按易变化的文件名：

- 2.8.4：{len(a)} 个任务；master：{len(b)} 个任务。
- 相同 ID：{comparison['common_ids']}；数据完全相同：{identical}。
- 相同 ID 但数据不同：{len(changes)}。其中任务定义差异 {comparison['task_definition_changes']}、奖励差异 {comparison['reward_definition_changes']}、前置差异 {comparison['prerequisite_changes']}。
- 仅见于 2.8.4：{len(comparison['release_only'])}；仅见于 master：{len(comparison['master_only'])}。
- 这些数字反映静态数据差异，可能含汉化、序列化或逻辑变化，不等同于已确认的功能增减。

## 后续使用

1. 用原版 JAR 的真实 mod ID、资源路径和实现位置核对现代对应项，不能仅凭名称猜测。
2. 缺少现代对应项的材料/机器/魔法功能继续列为未完成；不能换一个不同功能模组就宣称等价。
3. 贴图迁移必须核对命名空间、大小写、模型引用、着色和动画元数据，再进行客户端渲染验证。
4. 保留 2.8.4 与 2.9.0-RC-2 来源差异；旧 JAR 不放入 NeoForge 1.21.1 活动模组目录。

## 报告文件

- `summary.json`：来源身份与统计。
- `jar-inventory.json`：230 个 JAR 的哈希、mcmod.info、核心加载器信息及资源统计。
- `quest-id-comparison.json`：按任务 ID 的版本对照。
- `config-comparison.json`：配置文件路径与内容哈希对照。
- `case-collision-audit.json`：大小写碰撞记录。
- `core-implementation-locations.json`：核心实现类名索引。

**状态：辅助来源已接收并验证；完整无损迁移尚未完成。**
'''
(REPORT/'README.md').write_text(text,encoding='utf-8')
meta_path=OUT/'migration-metadata.json'; meta=json.loads(meta_path.read_text(encoding='utf-8-sig'))
meta['auxiliary_sources']=[{'name':'GT New Horizons 2.8.4 Java 17-25','path':'user-supplied/GT_New_Horizons_2.8.4_Java_17-25.zip','sha256':summary['archive_sha256'],'minecraft':'1.7.10','forge':'10.13.4.1614','role':'isolated legacy runtime and asset reference; does not replace the 2.9.0-RC-2 master baseline','top_level_jars':230,'report':'docs/reference-2.8.4/README.md'}]
meta['status']='in-progress-not-release'
meta['validation_state']={'portable_java_21':'version command passed','neoforge_server_installation':'21.1.262 installer completed successfully; game startup not yet verified','modern_artifacts':'10 downloaded and checksum verified; internal dependency mismatches found, corrections pending','original_release':'isolated extraction and JAR CRC passed; not executed','feature_parity':False,'client_rendering_verified':False}
meta_path.write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
readme=OUT/'README.md'; existing=readme.read_text(encoding='utf-8-sig')
heading='## Additional reference: GT New Horizons 2.8.4'
addition='\n\n'+heading+'\n\nA separately supplied original 1.7.10 instance contains 229 mod-directory JARs plus one launcher library. Its hash-verified reference inventory is documented in `docs/reference-2.8.4/README.md`. It supplements, but does not replace, the original 2.9.0-RC-2 master configuration source. Legacy JARs are kept isolated from the NeoForge instance.\n'
if heading not in existing: readme.write_text(existing+addition,encoding='utf-8')
print(json.dumps({k:v for k,v in comparison.items() if k not in ['changed','release_only','master_only']},ensure_ascii=False))
print('Reference report written:',REPORT/'README.md')
# Verify all new required references can be read back.
for n in ['README.md','summary.json','jar-inventory.json','quest-id-comparison.json','core-implementation-locations.json','case-collision-audit.json']:
    p=REPORT/n; assert p.is_file() and p.stat().st_size
assert json.loads(meta_path.read_text(encoding='utf-8'))['auxiliary_sources'][0]['sha256']==summary['archive_sha256']
print('REPORT_READBACK_OK')

"""Record the resumed phase from fresh checks, without upgrading gameplay claims."""
from pathlib import Path
import hashlib, json, shutil, zipfile

R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
Q=O/'docs/quest-migration/ftb-export'
V=O/'docs/verification'
P=R/'work/migration/retrieval-matcher-check'
E=R/'work/migration/retrieval-engine-check'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


for name in ['LegacyRetrievalStackMatcherCheck', 'LegacyCraftingShapeCheck']:
    source=P/(name+'.json')
    value=read(source)
    assert value['passed'],name
    shutil.copy2(source,Q/source.name)

matcher=read(Q/'LegacyRetrievalStackMatcherCheck.json')
engine=read(E/'LegacyRetrievalEngineCheck.json')
assert engine['passed'] and engine['source_algorithm_executed']
assert not engine['runtime_integrated'] and not engine['export_enabled']
for field, relative in {
    'engine_source_sha256':'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalEngine.java',
    'test_source_sha256':'work/migration/LegacyRetrievalEngineCheck.java',
    'runner_sha256':'work/migration/test_legacy_retrieval_engine.py',
}.items():
    assert engine[field]==sha(R/relative),relative
shutil.copy2(E/'LegacyRetrievalEngineCheck.json',Q/'LegacyRetrievalEngineCheck.json')
hunt=read(Q/'LegacyHuntAdapterCheck.json')
jar=O/'mods/gtnh-migration-compat-0.1.0.jar'
assert hunt['compat_jar_sha256']==sha(jar) and not hunt['isolated_dependency_root']
isolated=read(Q/'IsolatedMixinTransformationCheck.json')
assert isolated['passed'] and isolated['isolated_mixin_transformation_verified']
assert isolated['compat_jar_sha256']==sha(jar)
for relative,expected in isolated['source_sha256'].items():
    assert sha(R/relative)==expected,relative
for relative, expected in hunt['source_sha256'].items():
    assert sha(R/relative)==expected,relative
assert matcher['adapter_source_sha256']==sha(R/'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalStackMatcher.java')
with zipfile.ZipFile(jar) as compiled:
    engine_classes=sorted((E/'org/gtnhmigration/compat').glob('LegacyRetrievalEngine*.class'))
    assert engine_classes
    expected_engine_entries={path.relative_to(E).as_posix() for path in engine_classes}
    packaged_engine_entries={name for name in compiled.namelist() if name.startswith('org/gtnhmigration/compat/LegacyRetrievalEngine') and name.endswith('.class')}
    assert packaged_engine_entries==expected_engine_entries,'Stale or missing engine class'
    for path in engine_classes:
        assert compiled.read(path.relative_to(E).as_posix())==path.read_bytes(), 'Packaged engine differs from tested engine'
    for name in ['LegacyRetrievalStackMatcher','LegacyCraftingStackMatcher','LegacyRetrievalItemTaskMixin','LegacyCraftingItemTaskMixin']:
        entry='org/gtnhmigration/compat/'+name+'.class'
        assert compiled.read(entry)==(P/entry).read_bytes(), 'Packaged class differs from tested class: '+name
checks={}
for name in ['IsolatedMixinTransformationCheck','LegacyRetrievalEngineCheck','LegacyRetrievalStackMatcherCheck','LegacyCraftingShapeCheck','LegacyHuntAdapterCheck',
             'QuestAdapterShapeCheck','LegacyRetrievalCounterCheck','QuestSnbtCheck',
             'checkpoint-verification','crafting-task-adapter','hunt-task-adapter','location-task-boundary']:
    checks[name]=read(Q/(name+'.json'))
    assert checks[name]['passed'],name

manifest=read(Q/'export-manifest.json')
runtime=read(V/'test-runtime-files.json')
compat_row=next(row for row in runtime['files'] if row['path']=='mods/'+jar.name)
assert compat_row['sha256']==sha(jar)
assert 'eula=false' in (O/'eula.txt').read_text(encoding='utf-8')
report={
    'phase_date_shanghai':'2026-10-18',
    'phase':'restored checkpoint; retrieval/crafting durability and hunt subtype semantics',
    'checks':checks,
    'compat_jar_sha256':sha(jar),
    'source_quests':manifest['source_quests'],
    'structural_candidates':manifest['structurally_supported_candidates'],
    'dependency_closed_active_quests':manifest['dependency_closed_active_quests'],
    'remaining_quests_not_exported':manifest['source_quests']-manifest['dependency_closed_active_quests'],
    'runtime_inputs_hashed':len(runtime['files']),
    'full_parity':False,
    'game_runtime_verified':False,
    'isolated_mixin_transformation_verified':True,
    'live_mixin_transformation_verified':False,
    'neoforge_transform_chain_verified':False,
    'client_visuals_verified':False,
    'eula_changed':False,
    'source_archive_audit':'evidence/source-archive-audit.json in the source checkpoint; asset hashes do not prove visual parity',
}
(V/'task-semantics-phase.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
metadata=read(O/'migration-metadata.json')
metadata['last_phase']=report['phase']
metadata['validation_state'].update({
    'quest_adapter':'actual MC/FTB matcher and isolated Sponge Mixin application verified; full NeoForge transformer chain and game unverified',
    'full_feature_parity':False,'client_rendering_verified':False,
})
(O/'migration-metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
heading='## 2026-10-18 恢复与任务匹配修复'
status=O/'STATUS.zh-CN.md'
text=status.read_text(encoding='utf-8').split(heading)[0].rstrip()
text=text.replace('**2026-10-15（上海）。','**2026-10-18（上海更新）。',1).replace('## 本轮完成','## 累计完成',1)
text+='\n\n'+heading+'\n\n'
text+='已恢复锁定输入并重新执行本阶段验证。检索与合成的无 NBT 子集现在保留旧版耐久损耗规则，同时拒绝额外名称等不符组件；狩猎任务保留精确 ID 与子类匹配两种模式（包括牛与蘑菇牛的区别）。这些适配只作用于明确标记的迁移任务。\n\n'
text+=f"依赖闭合的实际任务配置仍为 {report['dependency_closed_active_quests']} / {report['source_quests']}；结构候选 {report['structural_candidates']} 个。修复检测语义没有冒充新增完整任务覆盖。\n\n"
text+='详细新证据见 docs/verification/task-semantics-phase.json。隔离 Sponge 服务已将五个注入应用到四个真实目标类，并通过 110 个方法的数据流检查和三组破坏性负例；完整 NeoForge 转换链、世界运行、多人全语义和客户端贴图仍未验证；完整迁移目标继续保留。\n'
text+=f"\n多物品检索纯引擎通过 {engine['source_comparisons']} 次锁定原源码算法对照，支持非消耗 AND 和单要求手动消耗，覆盖同槽预算、跨轮锁定、离线成员和重复提交。物品匹配、库存、存储和参与者仍使用夹具；尚未接入游戏或任务导出，不能计入已迁移任务数。\n"
status.write_text(text,encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},ensure_ascii=False))

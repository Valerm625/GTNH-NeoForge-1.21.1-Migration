"""Record the resumed phase from fresh checks, without upgrading gameplay claims."""
from pathlib import Path
import hashlib, json, shutil, zipfile

R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
Q=O/'docs/quest-migration/ftb-export'
V=O/'docs/verification'
P=R/'work/migration/retrieval-matcher-check'
E=R/'work/migration/retrieval-engine-check'
C=R/'work/migration/retrieval-progress-check'
L=R/'work/migration/quest-planner-check'
I=R/'work/migration/inventory-observer-check'


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
codec=read(C/'LegacyRetrievalProgressCodecCheck.json')
planner=read(L/'LegacyQuestRetrievalPlannerCheck.json')
for value in [codec,planner]:
    assert value['passed'] and not value['runtime_integrated'] and not value['export_enabled']
for field,relative in {
    'codec_source_sha256':'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalProgressCodec.java',
    'test_source_sha256':'work/migration/LegacyRetrievalProgressCodecCheck.java',
    'runner_sha256':'work/migration/test_legacy_retrieval_progress_codec.py',
}.items():
    assert codec[field]==sha(R/relative),relative
for relative,expected in planner['source_sha256'].items():
    assert sha(R/relative)==expected,relative
for directory,name in [(C,'LegacyRetrievalProgressCodecCheck'),(L,'LegacyQuestRetrievalPlannerCheck'),(I,'LegacyInventoryObserverCheck')]:
    shutil.copy2(directory/(name+'.json'),Q/(name+'.json'))
observer=read(Q/'LegacyInventoryObserverCheck.json')
assert observer['passed']
for name,expected in observer['adapter_source_sha256'].items():
    assert sha(R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java'))==expected,name
assert observer['test_source_sha256']==sha(R/'work/migration/LegacyInventoryObserverCheck.java')
assert observer['runner_sha256']==sha(R/'work/migration/test_legacy_inventory_observer.py')
hunt=read(Q/'LegacyHuntAdapterCheck.json')
jar=O/'mods/gtnh-migration-compat-0.1.0.jar'
assert observer['compat_jar_sha256']==sha(jar)
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
    for directory,prefix in [(C,'LegacyRetrievalProgressCodec'),(L,'LegacyQuestRetrievalPlanner')]:
        classes=sorted((directory/'org/gtnhmigration/compat').glob(prefix+'*.class'))
        assert classes,prefix
        expected_entries={path.relative_to(directory).as_posix() for path in classes}
        packaged_entries={name for name in compiled.namelist() if name.startswith('org/gtnhmigration/compat/'+prefix) and name.endswith('.class')}
        assert packaged_entries==expected_entries,'Stale or missing model class: '+prefix
        for path in classes:
            assert compiled.read(path.relative_to(directory).as_posix())==path.read_bytes(),'Packaged model differs from tested model: '+prefix
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
             'LegacyRetrievalProgressCodecCheck','LegacyQuestRetrievalPlannerCheck','LegacyInventoryObserverCheck',
             'QuestAdapterShapeCheck','LegacyRetrievalCounterCheck','QuestSnbtCheck',
             'checkpoint-verification','crafting-task-adapter','hunt-task-adapter','location-task-boundary']:
    checks[name]=read(Q/(name+'.json'))
    assert checks[name]['passed'],name
ore=read(V/'exact-ore-resource-check.json')
ore_tests=read(V/'exact-ore-resource-tests.json')
assert ore['passed'] and ore_tests['passed']
assert ore['input_sha256']['porter']==sha(R/'work/migration/port_exact_ore_resources.py')
assert ore['input_sha256']['verifier']==sha(R/'work/migration/verify_exact_ore_resources.py')
for field,filename in [('test_source_sha256','test_exact_ore_resources.py'),('porter_source_sha256','port_exact_ore_resources.py'),('verifier_source_sha256','verify_exact_ore_resources.py')]:
    assert ore_tests[field]==sha(R/'work/migration'/filename),filename
checks['exact_ore_resources']=ore
checks['exact_ore_resource_tests']=ore_tests

manifest=read(Q/'export-manifest.json')
runtime=read(V/'test-runtime-files.json')
compat_row=next(row for row in runtime['files'] if row['path']=='mods/'+jar.name)
assert compat_row['sha256']==sha(jar)
assert 'eula=false' in (O/'eula.txt').read_text(encoding='utf-8')
report={
    'phase_date_shanghai':'2026-10-18',
    'phase':'inventory detection timing; retrieval persistence and ordered planner models; exact mainline ore resource bytes',
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
    'retrieval_progress_codec':'source-compared actual NBT model; no save bridge',
    'quest_retrieval_planner':'source-compared ordered model; no inventory transaction',
    'inventory_observer':'source queue comparison and actual menu/compiled bridge checks; game events unverified',
    'ore_resource_subset':'34 exact primary-source PNGs and 34 transparent derived layers; rendering unverified',
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
text+=f"详细新证据见 docs/verification/task-semantics-phase.json。隔离 Sponge 服务已将 {isolated['injections_verified']} 个注入应用到 {isolated['targets_transformed']} 个真实目标类，并通过 {isolated['asm_basic_dataflow_methods']} 个方法的数据流检查和三组破坏性负例；完整 NeoForge 转换链、世界运行、多人全语义和客户端贴图仍未验证；完整迁移目标继续保留。\n"
text+=f"\n多物品检索纯引擎通过 {engine['source_comparisons']} 次锁定原源码算法对照，支持非消耗 AND 和单要求手动消耗，覆盖同槽预算、跨轮锁定、离线成员和重复提交。物品匹配、库存、存储和参与者仍使用夹具；尚未接入游戏或任务导出，不能计入已迁移任务数。\n"
text+=f"\n新增进度编解码模型通过 {codec['source_comparisons']} 次源码对照与 {codec['binary_nbt_roundtrips']} 次真实 1.21 NBT 往返；有序任务规划模型通过 {planner['source_comparisons']} 次源码对照。两者仍未接入游戏存档、库存事务或导出。\n"
text+='\n已接入标记检索任务的库存变化排队：包含空槽变化及初始监听器同步，在 Pre tick 重新扫描。完整游戏事件执行仍未验证。\n'
text+=f"\n{ore['primary_base_pngs_verified_from_archive_through_target']} 张矿石基础图已与锁定主线源码归档逐字节一致；另外 {ore['derived_secondary_pngs_verified_transparent']} 张透明派生层不算原图。仍需客户端渲染、加载优先级及材质着色验证。\n"
status.write_text(text,encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},ensure_ascii=False))

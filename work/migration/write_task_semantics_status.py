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
J=R/'work/migration/typed-nbt-json-check'
G=R/'work/migration/quest-progress-envelope-check'
T=R/'work/migration/progress-import-pipeline-check'
S=R/'work/migration/quest-progress-store-check'
B=R/'work/migration/retrieval-inventory-check'
D=R/'work/migration/retrieval-transaction-check'
F=R/'work/migration/retrieval-proposal-check'
H=R/'work/migration/player-file-check'
K=R/'work/migration/retrieval-coordinator-check'
W=R/'work/migration/world-player-authority-check'
N=R/'work/migration/integrated-owner-check'
U=R/'work/migration/retrieval-save-scope-check'
X=R/'work/migration/quest-world-store-check'
A=R/'work/migration/quest-player-admission-check'
M=R/'work/migration/player-marker-upgrade-check'
Y=R/'work/migration/owner-transaction-recovery-check'
Z=R/'work/migration/startup-shutdown-check'
AA=R/'work/migration/maintenance-initialization-check'
AB=R/'work/migration/quest-definition-archive-check'


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
for directory,name in [(J,'LegacyTypedNbtJsonReaderCheck'),(G,'LegacyQuestProgressEnvelopeCheck'),(S,'LegacyQuestProgressStoreCheck'),(T,'LegacyProgressImportPipelineCheck'),(B,'LegacyRetrievalInventoryBridgeCheck'),(D,'LegacyRetrievalTransactionJournalCheck'),(F,'LegacyRetrievalTransactionProposalCheck'),(H,'LegacyPlayerDataFileReaderCheck'),(K,'LegacyRetrievalCommitCoordinatorCheck'),(W,'LegacyWorldPlayerAuthorityCheck'),(N,'LegacyIntegratedOwnerCoordinatorCheck'),(U,'LegacyRetrievalSaveScopeCheck'),(X,'LegacyQuestWorldStoreCheck'),(A,'LegacyQuestPlayerAdmissionCheck'),(M,'LegacyPlayerMarkerUpgradeCheck'),(Y,'LegacyOwnerTransactionRecoveryCheck'),(Z,'LegacyStartupShutdownCheck'),(AA,'LegacyMaintenanceInitializationCheck'),(AB,'LegacyQuestDefinitionArchiveCheck')]:
    value=read(directory/(name+'.json'))
    assert value['passed'] and not value['runtime_integrated'] and not value['export_enabled'],name
    for relative,expected in value['source_sha256'].items():
        assert sha(R/relative)==expected,relative
    shutil.copy2(directory/(name+'.json'),Q/(name+'.json'))
hunt=read(Q/'LegacyHuntAdapterCheck.json')
jar=O/'mods/gtnh-migration-compat-0.1.0.jar'
assert observer['compat_jar_sha256']==sha(jar)
assert hunt['compat_jar_sha256']==sha(jar) and not hunt['isolated_dependency_root']
isolated=read(Q/'IsolatedMixinTransformationCheck.json')
assert isolated['passed'] and isolated['isolated_mixin_transformation_verified']
assert isolated['compat_jar_sha256']==sha(jar)
for relative,expected in isolated['source_sha256'].items():
    assert sha(R/relative)==expected,relative
native_scope=read(Q/'NativeSaveScopeMixinCheck.json')
assert native_scope['passed'] and native_scope['compat_jar_sha256']==sha(jar)
assert native_scope['injections_verified']==46 and not native_scope['game_runtime_verified']
assert native_scope['client_distribution_isolated_transform_verified']
assert native_scope['dedicated_distribution_check']['passed'] and native_scope['dedicated_distribution_check']['injections_verified']==45
for relative,expected in native_scope['source_sha256'].items():
    assert sha(R/relative)==expected,relative
for relative, expected in hunt['source_sha256'].items():
    assert sha(R/relative)==expected,relative
assert matcher['adapter_source_sha256']==sha(R/'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalStackMatcher.java')
with zipfile.ZipFile(jar) as compiled:
    for directory,prefix in [(C,'LegacyRetrievalProgressCodec'),(L,'LegacyQuestRetrievalPlanner'),(J,'LegacyTypedNbtJsonReader'),(G/'classes','LegacyQuestProgressEnvelope'),(S,'LegacyQuestProgressStoreCodec'),(S,'LegacyQuestProgressFileStore'),(B,'LegacyRetrievalInventoryBridge'),(D,'LegacyRetrievalTransactionJournal'),(F,'LegacyRetrievalTransactionProposal'),(H,'LegacyPlayerDataFileReader'),(K,'LegacyRetrievalCommitCoordinator'),(W,'LegacyPlayerDataFileReader'),(W,'LegacyRetrievalTransactionJournal'),(W,'LegacyRetrievalCommitCoordinator'),(N,'LegacyRetrievalCommitCoordinator'),(N,'LegacyRetrievalTransactionProposal'),(U,'LegacyRetrievalSaveScope'),(K,'LegacyRetrievalNativeSaveScope'),(X,'LegacyQuestWorldStore'),(A,'LegacyQuestPlayerAdmission'),(A,'LegacyQuestNativeLifecycle'),(M,'LegacyPlayerMarkerUpgrade'),(M,'LegacyPlayerNbtPatch'),(Y,'LegacyOwnerTransactionRecovery'),(Z,'LegacyRetrievalSaveScope'),(AA,'LegacyMaintenanceInitialization'),(AB,'LegacyQuestDefinitionArchive')]:
        package_dir=directory/'org/gtnhmigration/compat'
        classes=[package_dir/(prefix+'.class')]+sorted(package_dir.glob(prefix+'$*.class'))
        assert all(path.is_file() for path in classes),prefix
        expected_entries={path.relative_to(directory).as_posix() for path in classes}
        stem='org/gtnhmigration/compat/'+prefix
        packaged_entries={name for name in compiled.namelist() if name==stem+'.class' or name.startswith(stem+'$') and name.endswith('.class')}
        assert packaged_entries==expected_entries,'Stale or missing model class: '+prefix
        for path in classes:
            assert compiled.read(path.relative_to(directory).as_posix())==path.read_bytes(),'Packaged model differs from tested model: '+prefix
    for path in (T/'org/gtnhmigration/compat').glob('*.class'):
        assert compiled.read(path.relative_to(T).as_posix())==path.read_bytes(),'Composition used a different model class: '+path.name
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
for name in ['IsolatedMixinTransformationCheck','NativeSaveScopeMixinCheck','LegacyRetrievalSaveScopeCheck','LegacyQuestWorldStoreCheck','LegacyQuestPlayerAdmissionCheck','LegacyPlayerMarkerUpgradeCheck','LegacyOwnerTransactionRecoveryCheck','LegacyStartupShutdownCheck','LegacyMaintenanceInitializationCheck','LegacyQuestDefinitionArchiveCheck','LegacyRetrievalEngineCheck','LegacyRetrievalStackMatcherCheck','LegacyCraftingShapeCheck','LegacyHuntAdapterCheck',
             'LegacyRetrievalProgressCodecCheck','LegacyQuestRetrievalPlannerCheck','LegacyInventoryObserverCheck',
             'LegacyTypedNbtJsonReaderCheck','LegacyQuestProgressEnvelopeCheck','LegacyQuestProgressStoreCheck','LegacyProgressImportPipelineCheck','LegacyRetrievalInventoryBridgeCheck','LegacyRetrievalTransactionJournalCheck','LegacyRetrievalTransactionProposalCheck','LegacyPlayerDataFileReaderCheck','LegacyRetrievalCommitCoordinatorCheck','LegacyWorldPlayerAuthorityCheck','LegacyIntegratedOwnerCoordinatorCheck',
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
collisions=read(R/'evidence/exact-resource-collisions.json')
collision_tests=read(R/'evidence/exact-resource-collision-tests.json')
assert collisions['passed'] and collision_tests['passed']
assert collisions['auditor_source_sha256']==sha(R/'work/migration/audit_exact_resource_collisions.py')
assert collision_tests['auditor_source_sha256']==collisions['auditor_source_sha256']
assert collision_tests['test_source_sha256']==sha(R/'work/migration/test_exact_resource_collisions.py')
assert collisions['full_manifest_sha256']==sha(R/'work/migration/resource-collision-check/asset-manifest.json')
checks['exact_resource_collisions']=collisions
checks['exact_resource_collision_tests']=collision_tests

archive=read(O/'docs/quest-migration/definition-archive.json')
assert archive['passed'] and archive['source_document_count']==7806 and archive['source_quest_count']==3814
for relative,expected in archive['source_sha256'].items():assert sha(R/relative)==expected,relative
assert archive['archive_sha256']==checks['LegacyQuestDefinitionArchiveCheck']['archive_sha256']
assert sha(O/'config/gtnh_migration/quest-definitions'/(archive['archive_sha256']+'.bin'))==archive['archive_sha256']
with zipfile.ZipFile(jar) as compiled:
    assert compiled.read('gtnh_migration/quest-definitions.pin')==(R/'work/compat-mod/src/main/resources/gtnh_migration/quest-definitions.pin').read_bytes()
checks['source_definition_archive']={k:v for k,v in archive.items() if k!='documents'}
assert checks['LegacyStartupShutdownCheck']['compat_jar_sha256']==sha(jar)
manifest=read(Q/'export-manifest.json')
runtime=read(V/'test-runtime-files.json')
for relative,expected in runtime['source_sha256'].items():assert sha(R/relative)==expected,relative
checks['source_definition_archive']['source_sha256'].update(runtime['source_sha256'])
archive_rows=[row for row in runtime['files'] if row['path']=='config/gtnh_migration/quest-definitions/'+archive['archive_sha256']+'.bin']
assert len(archive_rows)==1 and archive_rows[0]['sha256']==archive['archive_sha256'] and archive_rows[0]['bytes']==archive['archive_bytes']
compat_row=next(row for row in runtime['files'] if row['path']=='mods/'+jar.name)
assert compat_row['sha256']==sha(jar)
assert 'eula=false' in (O/'eula.txt').read_text(encoding='utf-8')
report={
    'phase_date_shanghai':'2026-10-18',
    'phase':'journal preparation and recovery with installed startup/player-load call sites, source receipts, archived binary-preserving marker promotion, proven owner mirror/cache reconciliation, non-saving cleanup for pre-world recovery failures and world-bound auxiliary construction recovery; complete pinned source definition archive and native pre-recovery binding check installed; native lifecycle execution and full game parity remain incomplete',
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
    'tested_model_classes_match_packaged_jar':True,
    'typed_json_envelope_planner_composition_verified':True,
    'independent_uuid_ledger_file_roundtrip_verified':True,
    'ledger_process_halt_recovery_verified':True,
    'actual_inventory_in_memory_count_application_verified':True,
    'inventory_and_progress_transaction_verified':False,
    'journal_fixture_two_file_recovery_verified':True,
    'actual_inventory_to_journal_proposal_composition_verified':True,
    'authoritative_player_file_readback_verified':True,
    'coordinated_submission_fixture_verified':True,
    'native_player_save_adapter_compiled':True,
    'world_player_authority_fixture_verified':True,
    'singleplayer_load_authority_bytecode_audited':True,
    'integrated_owner_cache_and_world_save_bytecode_audited':True,
    'integrated_owner_submission_fixture_verified':True,
    'integrated_owner_native_adapter_compiled':True,
    'native_save_permit_state_machine_verified':True,
    'native_save_guard_mixins_isolated_verified':True,
    'native_save_guard_game_runtime_verified':False,
    'world_bound_ledger_fixture_verified':True,
    'world_bound_offline_process_recovery_verified':True,
    'native_player_admission_call_sites_registered':True,
    'player_admission_receipt_fixture_verified':True,
    'fresh_process_admission_fixture_verified':True,
    'archived_marker_upgrade_fixture_verified':True,
    'marker_upgrade_process_halt_recovery_verified':True,
    'marker_binary_ieee_fidelity_fixture_verified':True,
    'known_owner_transaction_recovery_fixture_verified':True,
    'pre_world_startup_shutdown_fixture_verified':True,
    'maintenance_initialization_restart_fixture_verified':True,
    'source_definition_archive_byte_fidelity_verified':True,
    'native_definition_binding_call_order_audited':True,
    'definition_archive_game_runtime_verified':False,
    'legacy_ambiguous_initialization_recovery_verified':False,
    'loaded_world_failure_shutdown_verified':False,
    'owner_repair_process_halt_recovery_verified':True,
    'durable_admission_recovery_verified':False,
    'integrated_owner_native_submission_verified':False,
    'native_player_save_verified':False,
    'persistent_player_marker_scope_fixture_verified':True,
    'native_player_clone_verified':False,
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
    'legacy_progress_import':'typed JSON and identity envelope composed with planner and independent ledger reload; synthetic player progress, no world import',
    'uuid_progress_ledger':'versioned append-only NBT snapshots and source bytes preserved; process-halt recovery verified, no world lifecycle or inventory transaction',
    'retrieval_inventory_bridge':'real vanilla Inventory/ItemStack snapshots, count application/rollback and native-shaped player-NBT proposals tested; no world submit handler or native player save',
    'retrieval_transaction_journal':'expected-head preparation, immutable records and synthetic player-file recovery tested; actual inventory proposal composition and clone-persisted marker scope verified; no native persistence or world lifecycle',
    'authoritative_player_file_readback':'strict UUID.dat integrity, immutable raw-byte snapshots and file-backed commit/abort recovery tested with synthetic data; no fallback to dat_old, native save or lifecycle barrier',
    'retrieval_commit_coordinator':'baseline/marker promotion, intent, actual inventory counts, save readback, all-participant final recheck and conditional rollback tested; native adapters acquire guarded save scopes, no native execution or durable admission recovery',
    'native_save_scope':'single-use permits, callback/thread rejection and process-local quarantine tested; 46 required native save/load/lifecycle injections isolated-transformed; no game execution, external filesystem lock or arbitrary-mod inventory isolation',
    'world_player_authority':'level.dat/Data/Player readback and source-bound journal resolution tested; pinned native owner load/cache/save bytecode audited; explicit owner adapter compiled, synthetic coordination verified; no native lifecycle or game execution',
    'world_bound_ledger':'immutable world-relative manifest archived at genesis; historical catalog, non-creating reopen and offline primary/mirror recovery tested; startup/admission call sites installed but not game-executed',
    'player_admission':'source receipts, loaded owner cache checks, first-login absence, native payload return identity and post-event live marker checked in fixtures; native startup/load hooks registered and isolated-transformed; no game execution or unknown cache/mirror repair',
    'player_marker_upgrade':'world-bound maintenance intents, exact before/after gzip archives, targeted binary NBT edit, atomic mirror-first publication, actual cache promotion and process-halt roll-forward tested with synthetic files; native game execution and conflict repair unverified',
    'definition_archive':'7806 exact source files, 3814 quests, source settings and identity references retained; pinned content digest checked before bound-world recovery; retrieval progress shapes cataloged but gameplay handlers remain incomplete',
    'maintenance_initialization':'durable world/protocol intent before directory creation and separate completion witness; interrupted genesis completes without advancing main progress or changing player/world bytes; legacy ambiguous partial directories remain blocked',
    'startup_failure_shutdown':'failed loadLevel HEAD with no levels or players, terminal runServer and unwound poisoned scope only; saves skipped, actual lock reopen and unchanged synthetic files checked; native game and loaded-world failure shutdown unverified',
    'owner_transaction_recovery':'startup reconciliation of proven old/new WORLD_PLAYER mirror/cache states; immutable originals, binary-preserving mirror edit, world bytes unchanged, main decision and repair completion recoverable across process halt; unknown conflicts and native execution unverified',
    'source_asset_collisions':'four exact archives checked; all same-path candidates retained, precedence unresolved',
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
text+='\n旧格式 typed JSON、完整 quest UUID/task index/factory 进度外层、纯规划器与独立文件账本已串联验证；真实任务定义参与检查，玩家进度仍为合成数据。未知字段或身份整份拒绝并保留原件；未实现世界存档导入、库存事务或 FTB 完成/奖励桥接。现代 NBT 负零归一化仍是明确拒绝的值域缺口。\n'
ledger=checks['LegacyQuestProgressStoreCheck']
text+=f"\n独立 UUID 账本通过 {ledger['assertions']} 项断言、{ledger['rejected_codec_mutations']} 个格式拒绝案例及 {len(ledger['process_crash_checks'])} 个独立 JVM 中断恢复场景。保留完整原输入字节、历史帧和离线 UUID；损坏、未知版本、配置变化或链缺口明确阻止加载。尚未证明机器掉电恢复或与玩家库存跨文件原子性。\n"
bridge=checks['LegacyRetrievalInventoryBridgeCheck']
text+=f"\n真实 Minecraft 库存桥接通过 {bridge['assertions']} 项断言，含 {bridge['vanilla_default_stacks_roundtripped']} 个原版非空默认物品栈的快照往返。旧40槽全量复核、仅扣发起者、单次应用、异常回滚与不确定库存隔离已在实际Inventory对象上检查；Player/世界/物品身份映射仍未接入，不是完整游戏或跨文件事务验证。\n"
journal=checks['LegacyRetrievalTransactionJournalCheck']
proposal=checks['LegacyRetrievalTransactionProposalCheck']
text+=f"\n事务日志通过 {journal['assertions']} 项断言及 {len(journal['process_crash_checks'])} 个进程中断恢复场景；实际库存到玩家NBT候选、账本准备及恢复组合通过 {proposal['assertions']} 项断言。玩家文件写入器仍是夹具，未调用原生PlayerDataStorage；未证明世界库存/进度事务完成。\n"
reader=checks['LegacyPlayerDataFileReaderCheck']
text+=f"\n权威玩家文件读回通过 {reader['assertions']} 项断言：仅读取UUID.dat，严格校验单个gzip成员及NBT、身份、读取期间文件变化；当前文件缺失或损坏时不回退dat_old。原始压缩字节保留，文件读回可驱动日志提交/取消，恢复不改写玩家文件。玩家数据为合成夹具，尚未接入原生保存及世界生命周期隔离。\n"
coordinator=checks['LegacyRetrievalCommitCoordinatorCheck']
text+=f"\n提交协调器通过 {coordinator['assertions']} 项断言，将持久基线、准备日志、实际库存扣减、保存/读回及条件回滚串联。文件证明旧状态时恢复原物品对象；结果不明保留待决日志并阻止该库存/账本再次提交。原生ServerPlayer/PlayerDataStorage适配器已编译，执行的保存端仍为夹具；游戏保存/生命周期隔离及事件入口尚未接入。\n"
world_authority=checks['LegacyWorldPlayerAuthorityCheck']
text+=f"\n单人房主的level.dat/Data/Player来源和UUID.dat镜像不能混用。{world_authority['assertions']} 项断言验证完整世界文件快照、显式来源日志和关闭重开恢复，旧UUID日志保持兼容。原生登录分支、退出保存顺序与DataVersion写入经过锁定字节码核对。\n"
owner=checks['LegacyIntegratedOwnerCoordinatorCheck']
text+=f"\n新增显式房主提交路径：以level.dat决定事务，缓存和UUID镜像也必须一致；取消时通过玩家列表保存恢复二者，并复核世界文件未变。{owner['assertions']} 项夹具断言及 {owner['native_owner_save_audit']['negative_controls']} 组字节码破坏性负例通过，后续保存不会重新写入已取消扣物。原生适配器仅编译和审计，需覆盖全服务器保存/生命周期的持续隔离，尚未注册或执行真实游戏，仍未完成单人迁移。\n"
world_store=checks['LegacyQuestWorldStoreCheck']
text+=f"\n世界账本绑定通过 {world_store['assertions']} 项断言和 {len(world_store['process_crash_checks'])} 个独立JVM中断/重开场景。固定世界相对目录、不可变历史任务目录及首帧原件绑定阻止串账；缺失或不完整初始化不重置为空。离线恢复按待决来源读回，房主额外核对UUID镜像；离线恢复本身不改写玩家或世界文件；启动/登录调用点已注册，真实运行和未知缓存冲突修复仍未验证。\n"
admission=checks['LegacyQuestPlayerAdmissionCheck']
text+=f"\n玩家加载来源凭据通过 {admission['assertions']} 项断言、{len(admission['process_admission_checks'])} 个独立JVM中断后的恢复/加载凭据场景。注册世界启动、正常停止及玩家原生加载调用点，保留DataFixer和加载事件，并在事件后核对实际玩家标记。原生调用位置经过字节码和隔离变换检查，尚未运行真实世界；旧标记升级由独立维护日志实现并在下列夹具验证；未知缓存/镜像修复和安全故障关闭仍待完成。\n"
marker=checks['LegacyPlayerMarkerUpgradeCheck']
text+=f"\n旧事务标记升级通过 {marker['assertions']} 项断言和 {len(marker['process_crash_checks'])} 个独立JVM中断后恢复/加载场景。原/目标gzip完整归档，维护意图绑定世界与主账本版本，镜像先于世界文件原子发布，原生房主缓存只迁移自有标记。UUID和世界两份独立二进制对照保留其他字段、根名称、顺序、负零和NaN载荷；未知状态拒绝覆盖。仍是合成文件验证，未证明真实玩家加载/保存或原生NBT全流程保真。\n"
owner_recovery=checks['LegacyOwnerTransactionRecoveryCheck']
text+=f"\n房主中断事务恢复通过 {owner_recovery['assertions']} 项断言、16种状态组合与 {len(owner_recovery['process_crash_checks'])} 个独立JVM中断恢复/入场凭据场景。世界文件决定提交或取消，仅修复日志证明的旧/新镜像和缓存；原/目标完整归档，世界与备份不改写，其他模组字段保留。主账本决策前重新验证镜像与缓存；两个维护协议不得重叠。已经接到启动调用点，但未运行真实游戏，未知冲突及初始化/故障关闭仍未完成。\n"
initialization=checks['LegacyMaintenanceInitializationCheck']
text+=f"\n维护目录初始化通过 {initialization['assertions']} 项断言、24个独立JVM在创建边界中断后继续维护/入场凭据场景。创建意图先于目录建立，完成凭据独立保存；只补全已证明的空首帧，不重置正式历史，不改变主进度或玩家/世界原件。旧目录缺少创建证据时仍不自动修复，主世界初始化、实机和机器掉电持久性未验证。\n"
shutdown=checks['LegacyStartupShutdownCheck']
text+=f"\n启动恢复失败关闭通过 {shutdown['assertions']} 项断言，两种存储来源的真实目录锁和账本锁重开通过。只允许原生世界加载体尚未开始、没有玩家/世界且runServer已停止时跳过保存并关闭资源；未决事务与存档字节保留。旧对象永久失效，新的服务器可重新绑定路径。关闭异常保留隔离。原生调用点静态及隔离变换通过，真实游戏、已加载世界故障关闭和其他模组兼容性仍未验证。\n"
scope=checks['LegacyRetrievalSaveScopeCheck']
text+=f"\n保存许可状态机通过 {scope['assertions']} 项断言，保护嵌套保存的一次性入口、跨账本/回调/线程重入及未解决范围关闭。新增 {native_scope['injections_verified']} 处原生保存和生命周期Mixin在 {native_scope['targets_transformed']} 个固定目标上应用成功，{native_scope['asm_basic_dataflow_methods']} 个方法数据流检查及三组破坏性负例通过。协调器在决策前再次核对所有观察库存。原生钩子尚未在游戏执行；进程级隔离不等于持久登录恢复或任意模组库存写入隔离。\n"
text+=f"\n四份锁定旧源码共有 {collisions['source_resource_occurrences']} 个资源出现记录；{collisions['same_path_groups']} 组同路径候选中 {collisions['same_path_different_content_groups']} 组字节不同，双方来源均保留，未猜测加载优先级或覆盖目标资源。\n"
status.write_text(text,encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},ensure_ascii=False))

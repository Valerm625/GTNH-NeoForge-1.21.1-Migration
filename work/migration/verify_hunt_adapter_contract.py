"""Independently compare emitted hunt predicates to the complete legacy task fields."""
from pathlib import Path
import copy, hashlib, json

R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
D=O/'docs/quest-migration/ftb-export'
Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests'
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'))
ids=manifest['hunt_candidate_source_ids']
# Deliberately independent of legacy_hunt_tasks. No fuzzy ID matching.
identities={'Pig':'minecraft:pig','Cow':'minecraft:cow','WitherBoss':'minecraft:wither','EnderDragon':'minecraft:ender_dragon'}
# Independently retained from evidence/phase-2026-10-17-hunt-subset.json and
# checked against the pinned original quest tasks below. Do not derive this
# baseline from the exporter's output: losing an entire candidate must fail.
expected_candidates={
    '0:10': {'key':'0:10','target':'Pig','required':5,'subtypes':0},
    '0:42': {'key':'0:10','target':'Cow','required':6,'subtypes':1},
    '0:456': {'key':'0:10','target':'WitherBoss','required':1,'subtypes':1},
}
sources={}
source_quest_hashes={}
all_hunts=[]
for path in sorted(Q.rglob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'))
    qid=str(data['questIDHigh:4'])+':'+str(data['questIDLow:4'])
    for key, task in data.get('tasks:9',{}).items():
        if task.get('taskID:8')!='bq_standard:hunt': continue
        all_hunts.append(task)
        if qid in expected_candidates:
            fixture=expected_candidates[qid]
            assert key==fixture['key']
            assert task['target:8']==fixture['target'] and task['required:3']==fixture['required']
            assert task.get('subtypes:1',0)==fixture['subtypes']
            assert task['ignoreNBT:1']==1 and task.get('damageType:8','')==''
            tid=hashlib.sha256(('task\0'+qid+'/'+key).encode()).hexdigest()[:16].upper()
            assert tid not in sources
            sources[tid]=(qid,task)
            source_quest_hashes[qid]=hashlib.sha256(path.read_bytes()).hexdigest()
assert set(source_quest_hashes)==set(expected_candidates) and len(sources)==3


def check(rows, candidate_ids):
    assert len(candidate_ids)==len(expected_candidates) and set(candidate_ids)==set(expected_candidates)
    assert len(rows)==len(sources)
    assert {row['task']['id'] for row in rows}==set(sources)
    for row in rows:
        emitted=row['task']; qid, original=sources[emitted['id']]
        assert row['source_id']==qid
        assert original['ignoreNBT:1']==1 and not original.get('damageType:8','')
        assert original['target:8'] in identities
        assert emitted['entity']==identities[original['target:8']]
        assert emitted['value']==original['required:3'] and emitted['value']>0
        assert emitted['type']=='kill' and emitted['custom_name']=='' and emitted['nbt_filter']==''
        expected='subtypes' if original.get('subtypes:1',0) else 'exact'
        assert emitted['tags']==['gtnh_legacy_hunt_'+expected]
        assert not emitted.get('entityTypeTag')


rows=manifest['hunt_candidate_tasks']
assert rows,'Expected nonempty reviewed hunt candidates'
check(rows,ids)
mutations=[]
for kind in ('subtype','entity','count','name','nbt','entity_tag'):
    bad=copy.deepcopy(rows); t=bad[0]['task']
    if kind=='subtype': t['tags']=['gtnh_legacy_hunt_exact' if t['tags']==['gtnh_legacy_hunt_subtypes'] else 'gtnh_legacy_hunt_subtypes']
    elif kind=='entity': t['entity']='minecraft:chicken'
    elif kind=='count': t['value']+=1
    elif kind=='name': t['custom_name']='extra restriction'
    elif kind=='nbt': t['nbt_filter']='{Health:1.0f}'
    else: t['entityTypeTag']='minecraft:skeletons'
    try: check(bad,ids)
    except AssertionError: mutations.append(kind)
    else: raise AssertionError('Broken hunt predicate accepted: '+kind)
for kind in ('candidate_row_removed','candidate_and_source_id_removed'):
    bad=copy.deepcopy(rows)
    removed=bad.pop()
    bad_ids=list(ids) if kind=='candidate_row_removed' else [qid for qid in ids if qid!=removed['source_id']]
    try: check(bad,bad_ids)
    except AssertionError: mutations.append(kind)
    else: raise AssertionError('Missing hunt candidate accepted: '+kind)

bq=R/'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java/bq_standard/tasks/TaskHunt.java'
ftb=R/'work/exact-sources/FTB-Quests/v2101.1.36/common/src/main/java/dev/ftb/mods/ftbquests/quest/task/KillTask.java'
original=bq.read_text(encoding='utf-8'); modern=ftb.read_text(encoding='utf-8')
assert 'subtypes && !target.isAssignableFrom(subject)' in original
assert '!subtypes && !subjectID.equals(idName)' in original
assert '!ignoreNBT && !ItemComparison.CompareNBTTag(targetTags, subjectTags, true)' in original
assert 'entityTypeId.equals(RegistrarManager.getId(e.getType(), Registries.ENTITY_TYPE))' in modern
report={'passed':True,'hunt_candidate_source_ids':sorted(ids),'hunt_candidate_count':len(ids),
        'candidate_task_predicates_checked':len(rows),'source_hunt_tasks':len(all_hunts),
        'source_subtype_tasks':sum(bool(t.get('subtypes:1',0)) for t in all_hunts),
        'candidate_subtype_tasks':sum(t['task']['tags']==['gtnh_legacy_hunt_subtypes'] for t in rows),
        'deliberate_predicate_mutations_rejected':mutations,
        'independent_expected_candidate_source_ids':sorted(expected_candidates),
        'independent_expected_candidate_task_count':len(sources),
        'candidate_source_quest_sha256':source_quest_hashes,
        'export_manifest_sha256':hashlib.sha256((D/'export-manifest.json').read_bytes()).hexdigest(),
        'verifier_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'source_sha256':hashlib.sha256(bq.read_bytes()).hexdigest(),
        'target_source_sha256':hashlib.sha256(ftb.read_bytes()).hexdigest(),
        'entity_name_mapping':identities,'ignored_nbt_remains_ignored':True,
        'unsupported_nbt_and_damage_predicates_rejected':True,
        'actual_mixin_transformation_verified':False,'runtime_game_verified':False,'full_quest_parity':False}
(D/'hunt-task-adapter.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))

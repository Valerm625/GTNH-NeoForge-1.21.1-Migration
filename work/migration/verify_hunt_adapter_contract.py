"""Verify the conservative BetterQuesting hunt -> FTB kill subset."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
D=O/'docs/quest-migration/ftb-export'
Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests'
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'))
ids=manifest.get('hunt_candidate_source_ids',[])
expected={'Pig':'minecraft:pig','Cow':'minecraft:cow','WitherBoss':'minecraft:wither','EnderDragon':'minecraft:ender_dragon'}
checked=[]
for path in sorted(Q.rglob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'))
    qid=str(data['questIDHigh:4'])+':'+str(data['questIDLow:4'])
    if qid not in ids:
        continue
    found=False
    for task in data.get('tasks:9',{}).values():
        if task.get('taskID:8')!='bq_standard:hunt':
            continue
        found=True
        target=task.get('target:8')
        assert target in expected
        assert task.get('ignoreNBT:1',0)
        assert not task.get('damageType:8','')
        assert not task.get('targetNBT:10',{}).get('CustomName:8','')
        assert int(task.get('required:3',0))>=1
    assert found
    checked.append(qid)
assert sorted(checked)==sorted(ids)
report={'passed':True,'hunt_candidate_source_ids':sorted(ids),'hunt_candidate_count':len(ids),'entity_name_mapping':{k:expected[k] for k in sorted({json.loads(p.read_text(encoding='utf-8'))['tasks:9'][next(iter(json.loads(p.read_text(encoding='utf-8'))['tasks:9']))].get('target:8') for p in Q.rglob('*.json') if str(json.loads(p.read_text(encoding='utf-8')).get('questIDHigh:4'))+':'+str(json.loads(p.read_text(encoding='utf-8')).get('questIDLow:4')) in ids}) if k in expected},'nbt_damage_custom_name_rejected':True,'runtime_game_verified':False,'full_quest_parity':False}
(D/'hunt-task-adapter.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))

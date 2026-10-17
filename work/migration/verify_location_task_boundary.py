"""Verify dimension-only location semantics and the opt-in FTB adapter contract."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
D=O/'docs/quest-migration/ftb-export'
Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests'
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'))
rows=[]
for path in sorted(Q.rglob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'))
    qid=str(data['questIDHigh:4'])+':'+str(data['questIDLow:4'])
    for task in data.get('tasks:9',{}).values():
        if task.get('taskID:8')=='bq_standard:location': rows.append((qid,task))
assert len(rows)==34 and len({qid for qid,_ in rows})==33
safe_dims={-1:'minecraft:the_nether',0:'minecraft:overworld',1:'minecraft:the_end'}
for _,t in rows:
    assert t.get('range:3')==-1 and t.get('biome:3')==-1 and not t.get('structure:8') and not t.get('invert:1') and not t.get('taxiCabDist:1')
    assert all(t.get(k,0)==0 for k in ['posX:3','posY:3','posZ:3'])
parsed=json.loads((D/'actual-snbt-parsed.json').read_text(encoding='utf-8'))
active_locations=[]
for chapter in parsed.values():
    if not isinstance(chapter,dict): continue
    for quest in chapter.get('quests',[]):
        for task in quest.get('tasks',[]):
            if task.get('type')=='location': active_locations.append(task)
for task in active_locations:
    assert task.get('dimension') in safe_dims.values()
    assert task.get('tags')==['gtnh_legacy_dimension_only']
    assert len(task.get('position',[]))==3 and len(task.get('size',[]))==3
safe_count=sum(1 for _,t in rows if t.get('dimension:3') in safe_dims)
r={'passed':True,'legacy_location_task_count':len(rows),'legacy_location_quest_count':len({qid for qid,_ in rows}),'all_are_dimension_only_unbounded':True,'safe_source_dimension_task_count':safe_count,'finite_coordinate_box_candidates':0,'ftb_location_task_requires_finite_box':True,'exported_location_task_count':len(active_locations),'dimension_resource_keys':safe_dims,'explicit_opt_in_marker':'gtnh_legacy_dimension_only','runtime_game_verified':False,'full_quest_parity':False,'dependency_closed_active_quests':manifest['dependency_closed_active_quests']}
(D/'location-task-boundary.json').write_text(json.dumps(r,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(r,ensure_ascii=False))

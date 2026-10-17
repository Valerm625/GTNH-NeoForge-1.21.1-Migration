"""Record why the current BetterQuesting location tasks remain blocked."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export';Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests'
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'));rows=[]
for path in sorted(Q.rglob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'));qid=str(data['questIDHigh:4'])+':'+str(data['questIDLow:4'])
    for task in data.get('tasks:9',{}).values():
        if task.get('taskID:8')=='bq_standard:location':rows.append((qid,task))
assert len(rows)==34 and len({x[0] for x in rows})==33
assert all(t.get('range:3')==-1 and t.get('biome:3')==-1 and not t.get('structure:8') and not t.get('invert:1') and t.get('posX:3')==0 and t.get('posY:3')==0 and t.get('posZ:3')==0 for _,t in rows)
assert not any(task.get('type')=='location' for chapter in json.loads((D/'actual-snbt-parsed.json').read_text(encoding='utf-8')).values() if isinstance(chapter,dict) for quest in chapter.get('quests',[]) for task in quest.get('tasks',[]))
r={'passed':True,'legacy_location_task_count':len(rows),'legacy_location_quest_count':len({x[0] for x in rows}),'all_are_dimension_only_unbounded':True,'finite_coordinate_box_candidates':0,'ftb_location_task_requires_finite_box':True,'safe_conversion_candidates':0,'explicit_export_blocker':'Location dimension-only semantics are not representable by FTB LocationTask','runtime_game_verified':False,'full_quest_parity':False,'dependency_closed_active_quests':manifest['dependency_closed_active_quests']}
(D/'location-task-boundary.json').write_text(json.dumps(r,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(r,ensure_ascii=False))

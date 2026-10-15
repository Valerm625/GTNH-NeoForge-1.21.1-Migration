"""Add verified existing modern implementations without declaring feature parity."""
from pathlib import Path
import json,sys,shutil,hashlib,zipfile,tomllib,concurrent.futures,datetime
sys.path.insert(0,str(Path(__file__).parent))
from resolve_mods import fetch,select,acquire,eligible,OUT,WORK
lockpath=OUT/'docs/artifacts.lock.json';lock=json.loads(lockpath.read_text(encoding='utf-8'));records=list(lock['artifacts']);seen={r['project_id'] for r in records};todo=[('railcraft-reborn',None),('ender-storage',None)];new=[]
while todo:
 slug,vid=todo.pop(0);p=fetch('project/'+slug)
 if p['id'] in seen:continue
 v=select(p['id'],vid);r=acquire(p['id'],v,p);records.append(r);new.append(r);seen.add(p['id'])
 print('NEW_SOURCE',p['slug'],v['version_number'],'license',p.get('license'),'deps',v.get('dependencies',[]),flush=True)
 for d in v.get('dependencies',[]):
  if d['dependency_type']=='required':
   pid=d.get('project_id') or fetch('version/'+d['version_id'])['project_id'];todo.append((pid,d.get('version_id')))
for r in new:
 assert 'neoforge' in r['loaders'] and '1.21.1' in r['game_versions']
 if r['server_side']!='unsupported':shutil.copy2(OUT/'mods'/r['filename'],WORK/'neoforge-smoke/mods'/r['filename'])
lock['artifacts']=sorted(records,key=lambda r:r['slug']);lock['roots']=list(dict.fromkeys(lock['roots']+['railcraft-reborn','ender-storage']));lock['selection']='Curated exact 1.21.1 NeoForge artifacts with internal metadata/embedded dependency checks. Added Railcraft Reborn and Ender Storage as candidates pending full functional regression.';lock['checked_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();lockpath.write_text(json.dumps(lock,ensure_ascii=False,indent=2),encoding='utf-8')
report={'new_artifacts':[{'project':r['slug'],'version':r['version'],'filename':r['filename'],'source_url':r['source_url'],'code_url':r.get('source_code_url'),'license':r['license']} for r in new],'original_functions_not_yet_audited':{'Railcraft':['track types and signaling','steam/boilers','multiblocks','coke/creosote production','item/fluid carts','GTNH-specific recipes and balance'],'EnderStorage':['three-color frequencies','owner/private channels','item/liquid transfer','cross-dimensional chunk lifecycle','GTNH gated recipes']},'runtime_tested':False,'feature_parity_verified':False}
(OUT/'docs/modern-transport-candidates.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('ADDED_COUNT',len(new),flush=True)

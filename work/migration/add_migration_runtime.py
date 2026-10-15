from pathlib import Path
import json,sys,shutil,zipfile,hashlib,concurrent.futures,subprocess
sys.path.insert(0,str(Path(__file__).parent))
from resolve_mods import fetch,select,eligible,acquire,ROOT,OUT,WORK
lockpath=OUT/'docs/artifacts.lock.json';lock=json.loads(lockpath.read_text(encoding='utf-8'));records=lock['artifacts']
queue=['architectury-api','kubejs'];seen={r['project_id'] for r in records}
while queue:
    slug=queue.pop(0);p=fetch('project/'+slug)
    if p['id'] in seen:continue
    v=select(p['id']);r=acquire(p['id'],v,p);records.append(r);seen.add(p['id'])
    print('ADDED_MODRINTH',p['slug'],v['version_number'],flush=True)
    for dep in v.get('dependencies',[]):
        if dep['dependency_type']=='required':
            if dep.get('version_id'): raise RuntimeError('Pinned dependency must be resolved explicitly: '+str(dep))
            queue.append(dep['project_id'])
ftb=json.loads((OUT/'docs/ftb-candidates.json').read_text(encoding='utf-8'))
for r in ftb:
    src=WORK/'cache/ftb'/r['filename'];assert hashlib.sha256(src.read_bytes()).hexdigest()==r['sha256']
    shutil.copy2(src,OUT/'mods'/r['filename']);r['active']=True
(OUT/'docs/ftb-candidates.json').write_text(json.dumps(ftb,ensure_ascii=False,indent=2),encoding='utf-8')
lock['artifacts']=records;lock['additional_artifact_lock']='docs/ftb-candidates.json'
lock['roots']=list(dict.fromkeys(lock['roots']+['kubejs','architectury-api']))
lockpath.write_text(json.dumps(lock,ensure_ascii=False,indent=2),encoding='utf-8')
for r in records:
    if r['server_side']!='unsupported':shutil.copy2(OUT/'mods'/r['filename'],WORK/'neoforge-smoke/mods'/r['filename'])
for r in ftb:shutil.copy2(OUT/'mods'/r['filename'],WORK/'neoforge-smoke/mods'/r['filename'])
# The common source artifact contains serialization implementation omitted by the NeoForge source classifier.
v=next(r['version'] for r in ftb if r['project']=='ftb-quests');name=f'ftb-quests-common-{v}-sources.jar'
url=f'https://maven.ftb.dev/releases/dev/ftb/mods/ftb-quests-common/{v}/{name}'
dest=WORK/'cache/ftb'/name
res=subprocess.run(['curl.exe','-fL','--connect-timeout','15','--max-time','45','-sS','-o',str(dest),url],capture_output=True,timeout=60)
if res.returncode==0:
    with zipfile.ZipFile(dest) as z:
        selected=[n for n in z.namelist() if n.endswith(('Quest.java','Chapter.java','ItemTask.java','CheckmarkTask.java','ItemReward.java','QuestFile.java'))]
        for n in selected:
            (WORK/'migration/ftb-source-snippets'/Path(n).name).write_bytes(z.read(n))
        print('COMMON_SOURCE_CLASSES',selected,flush=True)
else: print('COMMON_SOURCE_NOT_FOUND',res.stderr.decode(errors='replace')[:150],flush=True)
print('ACTIVE_MOD_JARS',len(list((OUT/'mods').glob('*.jar'))),flush=True)

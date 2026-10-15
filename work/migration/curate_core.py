raise SystemExit('Superseded: raw JEI bounds are handled by NeoForge VersionSupportMatrix. See verify_migration.py and current locks; do not rerun this obsolete candidate loop.')
from pathlib import Path
import sys,json,zipfile,io,tomllib,shutil,hashlib,datetime
sys.path.insert(0,str(Path(__file__).parent))
from resolve_mods import ROOT,OUT,WORK,fetch,acquire,eligible
lockpath=OUT/'docs/artifacts.lock.json'; original=json.loads(lockpath.read_text(encoding='utf-8'))
backup=WORK/'migration/artifacts.before-curation.json'
if not backup.exists(): backup.write_text(json.dumps(original,ensure_ascii=False,indent=2),encoding='utf-8')
records=list(original['artifacts']); quarantine=WORK/'quarantine/dependency-selection'; quarantine.mkdir(parents=True,exist_ok=True)
def quarantine_record(row,reason):
    for folder in [OUT/'mods',WORK/'neoforge-smoke/mods']:
        src=folder/row['filename']
        if src.exists():
            dst=quarantine/(('output-' if folder==OUT/'mods' else 'smoke-')+src.name)
            if dst.exists():
                if hashlib.sha256(dst.read_bytes()).digest()!=hashlib.sha256(src.read_bytes()).digest():raise RuntimeError('Quarantine name conflict')
                src.unlink()
            else: shutil.move(str(src),str(dst))
    return {'filename':row['filename'],'reason':reason,'action':'isolated outside active mods; not deleted from evidence'}
actions=[]
for row in list(records):
    if row['slug'] in ['ldlib','configuration']:
        # GTCEu already embeds tested first-generation LDLib and Configuration.
        actions.append(quarantine_record(row,'GTCEu bundles LDLib 1.0.35.a and Configuration 3.1.0; LDLib2 has a different modId and is not a replacement. Avoid independent versions overriding embedded dependencies.'))
        records.remove(row)
# Check actual latest JEI JAR, not website tags. Never patch third-party dependency bounds.
p=fetch('project/jei'); vs=sorted([v for v in fetch('project/'+p['id']+'/version') if eligible(v)],key=lambda v:v['date_published'],reverse=True)
selected=None
for v in vs[:8]:
    rec=acquire(p['id'],v,p)
    deps=rec['jar_metadata']['manifest'].get('dependencies',{}).get('jei',[])
    bound=next((d.get('versionRange','') for d in deps if d['modId']=='minecraft'),'')
    print('JEI_GAME_BOUND',rec['filename'],bound,flush=True)
    if bound in ['[1.21, 1.21.1]','[1.21.1]','[1.21.1,1.22)','[1.21,1.21.1]'] or ('1.21.1' in bound and bound.rstrip().endswith(']')):
        selected=rec;break
    actions.append(quarantine_record(rec,'Minecraft dependency interval excludes 1.21.1 or requires further review: '+bound))
if selected:
    for row in list(records):
        if row['slug']=='jei':
            if row['filename']!=selected['filename']: actions.append(quarantine_record(row,'Superseded with artifact whose internal dependency interval includes 1.21.1'))
            records.remove(row)
    records.append(selected)
    for d in selected.get('dependencies',[]):
        if d['dependency_type']=='required':
            dp=fetch('project/'+d['project_id']); vv=fetch('version/'+d['version_id']) if d.get('version_id') else max([v for v in fetch('project/'+dp['id']+'/version') if eligible(v)],key=lambda x:x['date_published'])
            if not any(r['project_id']==dp['id'] for r in records):records.append(acquire(dp['id'],vv,dp))
else: raise RuntimeError('No verified JEI candidate; manual audit required')
lock={**original,'artifacts':sorted(records,key=lambda r:r['slug']),'curation_actions':actions,'selection':'Curated exact NeoForge 1.21.1 versions; use GTCEu bundled first-generation libraries; inspect internal TOML bounds before runtime. Not full pack or parity verified.','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
lockpath.write_text(json.dumps(lock,ensure_ascii=False,indent=2),encoding='utf-8')
for r in records:
    if r['server_side']!='unsupported': shutil.copy2(OUT/'mods'/r['filename'],WORK/'neoforge-smoke/mods'/r['filename'])
print('CURATED',len(records),'artifacts',len(actions),'actions',flush=True)

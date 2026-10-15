"""Acquire exact NeoForge 1.21.1 artifacts, preserve provenance; never claim parity."""
from pathlib import Path
import json, urllib.request, urllib.parse, hashlib, datetime, time, zipfile, tomllib, shutil, concurrent.futures, sys
ROOT=Path(__file__).resolve().parents[2]; OUT=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'; WORK=ROOT/'work'
CACHE=WORK/'cache/modrinth'; CACHE.mkdir(parents=True,exist_ok=True)
MODS=OUT/'mods'; MODS.mkdir(exist_ok=True)
HEAD={'User-Agent':'GTNH-Migration/0.3 (personal compatibility test)'}
SEEDS=['gregtechceu-modern','ae2','jei','storagedrawers','jade','modernfix','ferrite-core']
def fetch(path):
    target=CACHE/(path.strip('/').replace('/','_').split('?')[0]+'.json')
    if target.exists(): return json.loads(target.read_text(encoding='utf-8'))
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request('https://api.modrinth.com/v2/'+path,headers=HEAD),timeout=25) as r: data=json.load(r)
            target.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8'); return data
        except Exception:
            if attempt==2: raise
            time.sleep(2)
def eligible(v):
    return '1.21.1' in v.get('game_versions',[]) and 'neoforge' in v.get('loaders',[]) and v.get('date_published','')[:10]<='2026-10-15'
def select(pid,version_id=None):
    if version_id:
        v=fetch('version/'+version_id)
        if not eligible(v): raise ValueError('Pinned dependency wrong platform: '+version_id)
        return v
    vs=[v for v in fetch('project/'+pid+'/version') if eligible(v)]
    if not vs: raise ValueError('No exact NeoForge 1.21.1 version: '+pid)
    # Prefer stable within compatible series; use alpha only when this project has no stable release.
    stable=[v for v in vs if v['version_type']=='release']
    return max(stable or vs,key=lambda v:v['date_published'])
def resolve():
    selected={}; projects={}; queue=[(s,None,'root') for s in SEEDS]; gaps=[]
    while queue:
        slug,vid,parent=queue.pop(0)
        try:
            p=fetch('project/'+slug); pid=p['id']; projects[pid]=p
            if pid in selected:
                if vid and selected[pid]['id']!=vid: raise ValueError('Conflicting exact dependency '+vid)
                continue
            v=select(pid,vid); selected[pid]=v
            print('SELECT',p['slug'],v['version_number'],v['version_type'],flush=True)
            for dep in v.get('dependencies',[]):
                if dep['dependency_type']!='required': continue
                dp=dep.get('project_id'); dv=dep.get('version_id')
                if not dp and dv: dp=fetch('version/'+dv)['project_id']
                if not dp: raise ValueError('Unidentified dependency '+str(dep))
                queue.append((dp,dv,pid))
        except Exception as e:
            gaps.append({'project':slug,'parent':parent,'error':str(e)}); print('GAP',slug,str(e),flush=True)
    return selected,projects,gaps

def acquire(pid,v,p):
    files=[f for f in v['files'] if f['filename'].endswith('.jar') and not any(x in f['filename'] for x in ['-sources','-javadoc','-dev.jar'])]
    f=next((x for x in files if x.get('primary')),files[0]); dest=MODS/f['filename']; h=f['hashes']; alg='sha512' if h.get('sha512') else 'sha1'
    if not dest.exists() or hashlib.new(alg,dest.read_bytes()).hexdigest()!=h[alg]:
        part=dest.with_suffix('.jar.part')
        import subprocess
        cmd=['curl.exe','-fL','--connect-timeout','15','--max-time','150','--retry','2','-sS','-o',str(part),f['url']]
        run=subprocess.run(cmd,capture_output=True,timeout=500)
        if run.returncode: raise RuntimeError(run.stderr.decode(errors='replace')[:300])
        if hashlib.new(alg,part.read_bytes()).hexdigest()!=h[alg]: raise ValueError('Downloaded hash mismatch '+f['filename'])
        part.replace(dest)
    with zipfile.ZipFile(dest) as z:
        manifest={}
        for path in ['META-INF/neoforge.mods.toml','META-INF/mods.toml']:
            if path in z.namelist(): manifest=tomllib.loads(z.read(path).decode('utf-8')); break
        if not manifest: raise ValueError('No NeoForge/Forge TOML metadata in '+dest.name)
        embedded=[]
        if 'META-INF/jarjar/metadata.json' in z.namelist(): embedded=json.loads(z.read('META-INF/jarjar/metadata.json'))
        meta={'manifest':manifest,'jarjar':embedded}
    record={'project_id':pid,'slug':p['slug'],'title':p['title'],'source_url':'https://modrinth.com/mod/'+p['slug'],'source_code_url':p.get('source_url'),'license':p.get('license'),'client_side':p['client_side'],'server_side':p['server_side'],'version_id':v['id'],'version':v['version_number'],'release_type':v['version_type'],'game_versions':v['game_versions'],'loaders':v['loaders'],'published':v['date_published'],'filename':f['filename'],'url':f['url'],'hashes':h,'size':dest.stat().st_size,'dependencies':v.get('dependencies',[]),'jar_metadata':meta,'status':'downloaded_hash_verified_not_runtime_or_parity_verified'}
    print('VERIFIED',dest.name,dest.stat().st_size,flush=True); return record
if __name__=='__main__':
    raise SystemExit('Discovery-only resolver retired: use the curated artifact locks; do not overwrite the active instance by automatic latest-version selection.')
    selected,projects,gaps=resolve(); records=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs={pool.submit(acquire,pid,v,projects[pid]):pid for pid,v in selected.items()}
        for f in concurrent.futures.as_completed(jobs):
            try: records.append(f.result())
            except Exception as e: gaps.append({'project':jobs[f],'error':str(e)}); print('DOWNLOAD_GAP',jobs[f],str(e),flush=True)
    lock={'schema':1,'minecraft':'1.21.1','loader':'neoforge','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'roots':SEEDS,'selection':'latest stable exact 1.21.1 NeoForge; alpha only if stable absent','artifacts':sorted(records,key=lambda r:r['slug']),'gaps':gaps,'parity_verified':False}
    (OUT/'docs/artifacts.lock.json').write_text(json.dumps(lock,ensure_ascii=False,indent=2),encoding='utf-8')
    smoke=WORK/'neoforge-smoke/mods'; smoke.mkdir(parents=True,exist_ok=True)
    for r in records:
        if r['server_side']!='unsupported': shutil.copy2(MODS/r['filename'],smoke/r['filename'])
    print('RESULT',len(records),'verified artifacts;',len(gaps),'gaps',flush=True)

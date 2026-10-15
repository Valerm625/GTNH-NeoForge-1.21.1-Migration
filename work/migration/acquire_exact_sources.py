from pathlib import Path
import json,subprocess,hashlib,zipfile,concurrent.futures
R=Path(__file__).resolve().parents[2];C=R/'work/cache/exact-sources';C.mkdir(parents=True,exist_ok=True)
def fetch(url,path,max_time=100):
    if path.exists() and path.stat().st_size:return
    p=path.with_suffix(path.suffix+'.part')
    r=subprocess.run(['curl.exe','-fL','--connect-timeout','12','--max-time',str(max_time),'--retry','1','-sS','-o',str(p),url],capture_output=True,timeout=2*max_time+30)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:500])
    p.replace(path)
def obtain(repo,version):
    api='https://api.github.com/repos/GTNewHorizons/'+repo+'/git/ref/tags/'+version
    meta=C/(repo+'-'+version+'-tag.json');fetch(api,meta)
    d=json.loads(meta.read_text(encoding='utf-8'));obj=d['object']
    if obj['type']=='tag':
        p=C/(repo+'-'+version+'-tag-object.json');fetch(obj['url'],p);obj=json.loads(p.read_text())['object']
    assert obj['type']=='commit',obj
    sha=obj['sha'];u='https://codeload.github.com/GTNewHorizons/'+repo+'/zip/'+sha
    archive=C/(repo+'-'+version+'.zip');fetch(u,archive,180)
    dest=R/'work/exact-sources'/repo/version;dest.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        prefix=z.namelist()[0].split('/')[0]+'/'
        for e in z.infolist():
            if e.is_dir():continue
            rel=e.filename.removeprefix(prefix);p=dest/rel
            assert p.resolve().is_relative_to(dest.resolve()) and ':' not in rel
            p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(e))
    lock={'repository':'GTNewHorizons/'+repo,'tag':version,'commit':sha,'tag_api':api,'archive_url':u,'archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'local_path':str(dest),'download_complete':True}
    (C/(repo+'-'+version+'-lock.json')).write_text(json.dumps(lock,indent=2),encoding='utf-8')
    print(json.dumps(lock),flush=True);return lock
if __name__=='__main__':
    import sys
    pairs=[('GT5-Unofficial','5.09.54.205')] if '--gregtech' in sys.argv else [('NewHorizonsCoreMod','2.9.84')]
    for repo,ver in pairs:
        try: obtain(repo,ver)
        except Exception as e: print('SOURCE_ERROR',repo,str(e),flush=True); raise

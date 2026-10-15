from pathlib import Path
import subprocess,json,hashlib,zipfile,concurrent.futures
R=Path(__file__).resolve().parents[2];C=R/'work/cache/exact-sources';C.mkdir(exist_ok=True)
def fetch(url,p):
 if p.exists() and p.stat().st_size:return
 temp=p.with_suffix(p.suffix+'.part');r=subprocess.run(['curl.exe','-fL','--connect-timeout','12','--max-time','100','--retry','1','-sS','-o',str(temp),url],capture_output=True,timeout=230)
 if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:400])
 temp.replace(p)
def obtain(owner,repo,tag):
 stem=repo+'-'+tag;api=f'https://api.github.com/repos/{owner}/{repo}/git/ref/tags/{tag}';meta=C/(stem+'-tag.json');fetch(api,meta);obj=json.loads(meta.read_text(encoding='utf-8'))['object']
 if obj['type']=='tag':
  p=C/(stem+'-tagobj.json');fetch(obj['url'],p);obj=json.loads(p.read_text(encoding='utf-8'))['object']
 assert obj['type']=='commit';commit=obj['sha'];url=f'https://codeload.github.com/{owner}/{repo}/zip/{commit}';arc=C/(stem+'.zip');fetch(url,arc);dest=R/'work/exact-sources'/repo/tag;dest.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(arc) as z:
  prefix=z.namelist()[0].split('/')[0]+'/'
  for e in z.infolist():
   if e.is_dir():continue
   rel=e.filename.removeprefix(prefix);p=dest/rel;assert p.resolve().is_relative_to(dest.resolve()) and ':' not in rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(e))
 d={'repository':owner+'/'+repo,'tag':tag,'commit':commit,'api':api,'archive_url':url,'archive_sha256':hashlib.sha256(arc.read_bytes()).hexdigest(),'source_root':str(dest)};(C/(stem+'-lock.json')).write_text(json.dumps(d,indent=2),encoding='utf-8');print(json.dumps(d),flush=True)
if __name__=='__main__':
 obtain('FTBTeam','FTB-Quests','v2101.1.36')

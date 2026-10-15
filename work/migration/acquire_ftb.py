from pathlib import Path
import json,xml.etree.ElementTree as ET,subprocess,hashlib,zipfile,tomllib,concurrent.futures
ROOT=Path(__file__).resolve().parents[2];CACHE=ROOT/'work/cache/ftb';OUT=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
def download(url,p):
    if p.exists():return
    q=p.with_suffix(p.suffix+'.part')
    r=subprocess.run(['curl.exe','-fL','--connect-timeout','15','--max-time','90','--retry','1','-sS','-o',str(q),url],capture_output=True,timeout=210)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:200])
    q.replace(p)
def acquire(name):
    tree=ET.fromstring((CACHE/(name+'-metadata.xml')).read_bytes())
    vs=[n.text for n in tree.findall('./versioning/versions/version') if n.text.startswith('2101.')]
    version=max(vs,key=lambda v:tuple(map(int,v.split('.')))); artifact=name+'-neoforge'
    base='https://maven.ftb.dev/releases/dev/ftb/mods/'+artifact+'/'+version+'/'+artifact+'-'+version
    jar=CACHE/(artifact+'-'+version+'.jar');download(base+'.jar',jar)
    hashfile=jar.with_suffix('.jar.sha1');download(base+'.jar.sha1',hashfile)
    expected=hashfile.read_text().split()[0];assert hashlib.sha1(jar.read_bytes()).hexdigest()==expected
    record={'project':name,'version':version,'artifact':artifact,'filename':jar.name,'url':base+'.jar','sha1':expected,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'downloaded':True,'active':False}
    with zipfile.ZipFile(jar) as z:
        for n in ['META-INF/neoforge.mods.toml','META-INF/mods.toml']:
            if n in z.namelist():record['metadata']=tomllib.loads(z.read(n).decode());break
        record['license_files']=[n for n in z.namelist() if 'license' in n.lower()]
    if name=='ftb-quests':
        try:
            src=CACHE/(artifact+'-'+version+'-sources.jar');download(base+'-sources.jar',src);record['sources_jar']=str(src)
        except Exception as e:record['source_download_error']=str(e)
    print(name,version,json.dumps(record.get('metadata',{}).get('dependencies',{})),flush=True)
    return record
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:records=list(ex.map(acquire,['ftb-quests','ftb-library','ftb-teams']))
(OUT/'docs/ftb-candidates.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
print('FTB_CANDIDATES_DOWNLOADED_CHECKSUM_VERIFIED',flush=True)

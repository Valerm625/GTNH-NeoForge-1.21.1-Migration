from pathlib import Path
import json, subprocess, hashlib, urllib.request, zipfile, datetime, shutil, os, re
ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'work'; CACHE=WORK/'cache'; CACHE.mkdir(exist_ok=True)
HEAD={'User-Agent':'GTNH-Migration-Audit/0.2 (personal local migration)'}
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers=HEAD),timeout=90) as r: return r.read()
def save(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
def download(url,path,digest=None):
    if path.exists() and (not digest or hashlib.sha256(path.read_bytes()).hexdigest()==digest): return
    temp=path.with_suffix(path.suffix+'.part')
    with urllib.request.urlopen(urllib.request.Request(url,headers=HEAD),timeout=120) as r,temp.open('wb') as f: shutil.copyfileobj(r,f)
    if digest and hashlib.sha256(temp.read_bytes()).hexdigest()!=digest: raise ValueError('SHA256 mismatch: '+path.name)
    temp.replace(path)
def probe(path):
    if not path.exists(): return {'path':str(path),'exists':False}
    try:
        p=subprocess.run([str(path),'-version'],capture_output=True,timeout=20)
        text=(p.stdout+p.stderr).decode('utf-8',errors='replace')
        return {'path':str(path),'exists':True,'returncode':p.returncode,'text':text,'valid_java21':p.returncode==0 and bool(re.search(r'version "21\.',text))}
    except Exception as e: return {'path':str(path),'error':str(e)}
if __name__=='__main__':
    paths=[Path(r'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe'),Path(r'C:\Program Files\Java\latest\jdk-21\bin\java.exe')]
    report={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'probes':[probe(p) for p in paths]}
    save(WORK/'migration/java-diagnosis.json',report)
    print(json.dumps(report,ensure_ascii=False),flush=True)
    java=next((Path(p['path']) for p in report['probes'] if p.get('valid_java21')),None)
    if not java:
        url='https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
        data=json.loads(get(url)); save(CACHE/'temurin21-metadata.json',data)
        asset=data[0]; package=asset['binary']['package']; arc=CACHE/package['name']
        print('Downloading official portable JDK:',package['name'],flush=True)
        download(package['link'],arc,package['checksum'])
        target=WORK/'runtime'; target.mkdir(exist_ok=True)
        with zipfile.ZipFile(arc) as z:
            for n in z.namelist():
                p=target/n
                if not p.resolve().is_relative_to(target.resolve()): raise ValueError('unsafe archive entry')
            z.extractall(target)
        java=next(target.glob('*/bin/java.exe'))
        check=probe(java); report['portable_probe']=check; report['portable_source']=url; report['portable_package']=package
        save(WORK/'migration/java-diagnosis.json',report)
        if not check.get('valid_java21'): raise RuntimeError('Portable Java 21 probe failed: '+str(check))
    save(WORK/'migration/runtime.json',{'java':str(java),'java_home':str(java.parent.parent),'probe':probe(java)})
    print('JAVA21_OK',str(java),flush=True)
    meta_url='https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml'
    import xml.etree.ElementTree as ET
    raw=get(meta_url); (CACHE/'neoforge-maven-metadata.xml').write_bytes(raw)
    versions=[e.text for e in ET.fromstring(raw).findall('./versioning/versions/version') if re.fullmatch(r'21\.1\.\d+',e.text or '')]
    version=max(versions,key=lambda v:tuple(map(int,v.split('.'))))
    url=f'https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar'
    jar=CACHE/f'neoforge-{version}-installer.jar'; sha=get(url+'.sha256').decode().strip().split()[0]
    download(url,jar,sha)
    save(WORK/'migration/platform-lock.json',{'minecraft':'1.21.1','neoforge':version,'java':21,'metadata_url':meta_url,'installer_url':url,'sha256':sha,'checked_at':report['checked_at']})
    target=WORK/'neoforge-smoke'; target.mkdir(exist_ok=True)
    print('Installing NeoForge',version,'at',target,flush=True)
    with (WORK/'migration/neoforge-install.log').open('w',encoding='utf-8') as log:
        p=subprocess.run([str(java),'-jar',str(jar),'--installServer',str(target)],cwd=target,stdout=log,stderr=subprocess.STDOUT,timeout=900)
    print('INSTALL_EXIT',p.returncode,flush=True)
    print((WORK/'migration/neoforge-install.log').read_text(encoding='utf-8',errors='replace')[-4500:],flush=True)
    if p.returncode: raise SystemExit(p.returncode)

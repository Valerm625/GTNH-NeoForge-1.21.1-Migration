from pathlib import Path
import json, subprocess, hashlib, xml.etree.ElementTree as ET, re, datetime, sys
ROOT=Path(__file__).resolve().parents[2]; WORK=ROOT/'work'; CACHE=WORK/'cache'; TARGET=WORK/'neoforge-smoke'
runtime=json.loads((WORK/'migration/runtime.json').read_text(encoding='utf-8')); java=runtime['java']
def download(url,path):
    p=subprocess.run(['curl.exe','-fL','--connect-timeout','15','--max-time','120','--retry','1','-sS','-o',str(path),url],capture_output=True,timeout=270)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
url='https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml'
meta=CACHE/'neoforge-maven-metadata.xml'; download(url,meta)
vs=[e.text for e in ET.fromstring(meta.read_bytes()).findall('./versioning/versions/version') if re.fullmatch(r'21\.1\.\d+',e.text or '')]
v=max(vs,key=lambda s:tuple(map(int,s.split('.')))); base=f'https://maven.neoforged.net/releases/net/neoforged/neoforge/{v}/neoforge-{v}-installer.jar'
jar=CACHE/f'neoforge-{v}-installer.jar'; sha=jar.with_suffix('.jar.sha256'); download(base+'.sha256',sha)
expected=sha.read_text().split()[0]
legacy=TARGET/jar.name
if not jar.exists() and legacy.exists() and hashlib.sha256(legacy.read_bytes()).hexdigest()==expected:
    import shutil; shutil.copy2(legacy,jar)
if not jar.exists(): download(base,jar)
assert hashlib.sha256(jar.read_bytes()).hexdigest()==expected,'Installer checksum mismatch'
lock={'minecraft':'1.21.1','neoforge':v,'java':21,'metadata_url':url,'installer_url':base,'sha256':expected,'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(WORK/'migration/platform-lock.json').write_text(json.dumps(lock,indent=2),encoding='utf-8')
print('Installer verified. NeoForge',v,flush=True)
with (WORK/'migration/neoforge-install.log').open('w',encoding='utf-8') as log:
    p=subprocess.run([java,'-Djava.net.preferIPv4Stack=true','-jar',str(jar),'--installServer',str(TARGET)],cwd=TARGET,stdout=log,stderr=subprocess.STDOUT,timeout=900)
print('INSTALL_EXIT',p.returncode,flush=True)
print((WORK/'migration/neoforge-install.log').read_text(encoding='utf-8',errors='replace')[-4000:],flush=True)
raise SystemExit(p.returncode)

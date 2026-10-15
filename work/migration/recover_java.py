import sys, json, urllib.request, urllib.parse, subprocess, zipfile, hashlib
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
from bootstrap import WORK,CACHE,save,probe
url='https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&os=windows&arch=x86&hw_bitness=64&archive_type=zip&java_package_type=jdk&javafx_bundled=false&release_status=ga&availability_types=CA&latest=true'
def bounded(url,path,seconds=60):
    p=subprocess.run(['curl.exe','-fL','--connect-timeout','15','--max-time',str(seconds),'--retry','1','--retry-delay','1','-sS','-o',str(path),url],capture_output=True,timeout=seconds*2+40)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace')[:1000])
meta=CACHE/'zulu21-metadata.json'; bounded(url,meta)
assets=json.loads(meta.read_text(encoding='utf-8'))
a=next(x for x in assets if 'win_x64' in x['name'] and '-fx-' not in x['name'])
print(json.dumps(a),flush=True)
detail=CACHE/'zulu21-package-detail.json'; bounded('https://api.azul.com/metadata/v1/zulu/packages/'+a['package_uuid'],detail)
d=json.loads(detail.read_text(encoding='utf-8'))
archive=CACHE/a['name']; part=archive.with_suffix('.zip.part')
print('Downloading direct Azul CDN',a['name'],flush=True)
bounded(a['download_url'],part,240)
hash_=hashlib.sha256(part.read_bytes()).hexdigest()
expected=d.get('sha256_hash') or d.get('sha256')
if not expected: raise RuntimeError('No published SHA256 in package metadata')
if hash_!=expected: raise RuntimeError('JDK SHA256 mismatch')
part.replace(archive)
target=WORK/'runtime'; target.mkdir(exist_ok=True)
with zipfile.ZipFile(archive) as z:
    for n in z.namelist():
        if not (target/n).resolve().is_relative_to(target.resolve()): raise ValueError('Unsafe ZIP entry')
    z.extractall(target)
java=next(target.glob('zulu*/bin/java.exe')); check=probe(java)
print(json.dumps(check),flush=True)
if not check.get('valid_java21'): raise RuntimeError('Java 21 is not operational')
save(WORK/'migration/runtime.json',{'java':str(java),'java_home':str(java.parent.parent),'probe':check,'source':url,'sha256':hash_,'package':a})
print('JAVA21_RUNTIME_READY',flush=True)

"""Reconstruct pinned NeoForge client bytecode for inspection, without launching Minecraft.
Uses only whitelisted pinned installer processors in an isolated ignored cache.
"""
from pathlib import Path
import hashlib,json,re,subprocess,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
C=R/'work/cache/native-client-audit';C.mkdir(parents=True,exist_ok=True)
JAVA=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
INSTALLER=R/'work/cache/neoforge-21.1.262-installer.jar'
assert hashlib.sha256(INSTALLER.read_bytes()).hexdigest()=='e86f5d78ed310465bd1ecf79823abcbc58782699b068ac776f4c5e226d994a8f'
def digest(p,kind='sha256'):return hashlib.new(kind,p.read_bytes()).hexdigest()
def download(url,path,sha1=None,size=None):
 assert url.startswith(('https://piston-meta.mojang.com/','https://piston-data.mojang.com/','https://maven.neoforged.net/'))
 if path.exists():
  if sha1:assert digest(path,'sha1')==sha1, str(path)
  if size is not None:assert path.stat().st_size==size,str(path)
  return
 temp=path.with_suffix(path.suffix+'.download')
 result=subprocess.run(['curl.exe','-fL','--connect-timeout','15','--max-time','180','--retry','1','-sS','-o',str(temp),url],capture_output=True,timeout=390)
 assert result.returncode==0,result.stderr.decode(errors='replace')
 if sha1:assert digest(temp,'sha1')==sha1,str(path)
 if size is not None:assert temp.stat().st_size==size,str(path)
 temp.replace(path)
lock_path=R/'locks/native-client-audit-inputs.json'
old=json.loads(lock_path.read_text(encoding='utf-8')) if lock_path.exists() else None
if old:
 version_entry=old['version_metadata']
else:
 manifest=C/'version-manifest-v2.json';download('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',manifest)
 entry=next(v for v in json.loads(manifest.read_text())['versions'] if v['id']=='1.21.1')
 version_entry={'url':entry['url'],'sha1':entry['sha1']}
version_file=C/'version-1.21.1.json';download(version_entry['url'],version_file,version_entry['sha1'])
version=json.loads(version_file.read_text());assert version['id']=='1.21.1'
client=version['downloads']['client'];maps=version['downloads']['client_mappings']
assert client['sha1']=='30c73b1c5da787909b2f73340419fdf13b9def88' and client['size']==26836906
for data,name in [(client,'client-1.21.1.jar'),(maps,'client-mappings.txt')]:download(data['url'],C/name,data['sha1'],data['size'])
with zipfile.ZipFile(INSTALLER) as z:
 profile=json.loads(z.read('install_profile.json'));patch=z.read('data/client.lzma')
patchfile=C/'client.lzma'
if patchfile.exists():assert patchfile.read_bytes()==patch
else:patchfile.write_bytes(patch)
neoform=O/'libraries/net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip'
with zipfile.ZipFile(neoform) as z:mappings=z.read('config/joined.tsrg')
mapfile=C/'joined.tsrg'
if mapfile.exists():assert mapfile.read_bytes()==mappings
else:mapfile.write_bytes(mappings)
libraries={row['name']:row['downloads']['artifact'] for row in profile['libraries']}
used_tools={}
def artifact(name):
 row=libraries[name];path=O/'libraries'/row['path']
 assert path.is_file() and digest(path,'sha1')==row['sha1'],name
 used_tools[row['path']]=digest(path)
 return path

def relative(p):return './'+str(Path(__import__('os').path.relpath(p,C))).replace('\\','/')
def process(index,outputs):
 p=profile['processors'][index]
 assert index in [5,6,8,9]
 args=[]
 mapping={'MAPPINGS':mapfile,'MOJMAPS':C/'client-mappings.txt','MERGED_MAPPINGS':C/'merged-mappings.txt','MINECRAFT_JAR':C/'client-1.21.1.jar','MC_SLIM':C/'client-slim.jar','MC_EXTRA':C/'client-extra.jar','MC_SRG':C/'client-srg.jar','PATCHED':C/'neoforge-21.1.262-client.jar','BINPATCH':patchfile}
 for a in p['args']:
  match=re.fullmatch(r'\{([A-Z_]+)\}',a)
  args.append(relative(mapping[match[1]]) if match else a)
 paths=[artifact(name) for name in dict.fromkeys([p['jar']]+p['classpath'])]
 with zipfile.ZipFile(paths[0]) as z:
  manifest=z.read('META-INF/MANIFEST.MF').decode().replace('\r\n ','').replace('\r','')
 main=re.search(r'^Main-Class: ([\w.$]+)$',manifest,re.M).group(1)
 input_names={5:['joined.tsrg','client-mappings.txt'],6:['client-1.21.1.jar','merged-mappings.txt'],8:['client-slim.jar','merged-mappings.txt'],9:['client-srg.jar','client.lzma']}[index]
 proof={'arguments':args,'tools':[digest(path) for path in paths],'inputs':{name:digest(C/name) for name in input_names}}
 stamp=C/f'processor-{index}.success.json'
 if stamp.is_file():
  previous=json.loads(stamp.read_text());assert all(previous[k]==v for k,v in proof.items()),'Processor input drift'
  assert previous['outputs']=={name:digest(C/name) for name in outputs},'Processor output drift'
  return
 assert not any((C/name).exists() for name in outputs),'Unverified processor output preserved; inspect before retrying'
 full=['-Xmx2G','-cp',';'.join(relative(path) for path in paths),main]+args
 def quote(value):return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=C/f'processor-{index}.args';argfile.write_text('\n'.join(quote(v) for v in full)+'\n',encoding='utf-8',newline='\n')
 print('CLIENT_AUDIT_PROCESSOR',index,p['jar'],flush=True)
 with (C/f'processor-{index}.log').open('wb') as log:
  result=subprocess.run([str(JAVA),'@'+argfile.name],cwd=C,stdout=log,stderr=subprocess.STDOUT,timeout=300)
 assert result.returncode==0, f'Processor {index} failed; inspect its local log'
 assert all((C/n).is_file() for n in outputs)
 proof['outputs']={name:digest(C/name) for name in outputs};stamp.write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8',newline='\n')
# Exact positions are checked against pinned metadata, not inferred execution.
assert profile['processors'][5]['args'][:2]==['--task','MERGE_MAPPING']
assert profile['processors'][6]['jar']=='net.neoforged.installertools:jarsplitter:2.1.2'
assert profile['processors'][8]['jar']=='net.neoforged:AutoRenamingTool:2.0.3:all'
assert profile['processors'][9]['jar']=='net.neoforged.installertools:binarypatcher:2.1.2:fatjar'
process(5,['merged-mappings.txt']);process(6,['client-slim.jar','client-extra.jar']);process(8,['client-srg.jar']);process(9,['neoforge-21.1.262-client.jar'])
result={'minecraft':'1.21.1','neoforge':'21.1.262','scope':'isolated bytecode inspection input; no client launch or EULA change','version_metadata':version_entry,'client_download':client,'client_mappings_download':maps,'installer_sha256':digest(INSTALLER),'neoform_sha256':digest(neoform),'processor_library_sha256':used_tools,
 'cache_files_sha256':{name:digest(C/name) for name in ['version-1.21.1.json','client-1.21.1.jar','client-mappings.txt','joined.tsrg','client.lzma','merged-mappings.txt','client-slim.jar','client-extra.jar','client-srg.jar','neoforge-21.1.262-client.jar']}}
if old:assert old==result,'Client audit input drift; preserve and inspect existing files'
else:lock_path.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'client_audit_input_verified':True,'patched_client_sha256':result['cache_files_sha256']['neoforge-21.1.262-client.jar'],'game_launched':False,'runtime_instance_changed':False}))

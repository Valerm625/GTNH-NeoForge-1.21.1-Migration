"""World binding, non-creating reopen and fresh-process offline recovery checks."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/quest-world-store-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
extra=minecraft.with_name('server-1.21.1-20240808.144430-extra.jar')
patched=O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar'
universal=patched.with_name('neoforge-21.1.262-universal.jar')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(patched)=='f57ce3018e21459d24b50508e8c1b4fd5c16324ec1dc5f316c192aa0130e7fe0'
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
cp=';'.join(relative(path) for path in [minecraft,extra]+libraries)
compile_cp=';'.join(relative(path) for path in [patched,universal])+';'+cp
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyPlayerDataFileReaderCheck.java',R/'work/migration/LegacyQuestWorldStoreCheck.java']
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=90)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+checks],'compile')
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestWorldStoreCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'world-store')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))

process_checks=[]
for boundary in ['DIRECTORY_CREATED','MANIFEST_PUBLISHED','GENESIS_PUBLISHED']:
 directory=Path(tempfile.mkdtemp(prefix='init-',dir=out))/'world'
 command=[str(java),'-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestWorldStoreCheck']
 result=subprocess.run(command+['init-crash',relative(directory),boundary],cwd=out,capture_output=True,timeout=60)
 assert result.returncode==73,result.stdout+result.stderr
 run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestWorldStoreCheck','recover-check',relative(directory),boundary],'recover-'+boundary)
 process_checks.append({'boundary':boundary,'halt_exit':73,'fresh_process_check':True,'expected':'open existing genesis' if boundary=='GENESIS_PUBLISHED' else 'refuse and preserve partial initialization'})
for authority in ['PLAYER_FILE','WORLD_PLAYER']:
 for decision in ['COMMIT','ABORT']:
  directory=Path(tempfile.mkdtemp(prefix='pending-',dir=out))/'world'
  result=subprocess.run([str(java),'-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestWorldStoreCheck','pending-crash',relative(directory),authority+':'+decision],cwd=out,capture_output=True,timeout=60)
  assert result.returncode==73,result.stdout+result.stderr
  run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestWorldStoreCheck','pending-check',relative(directory),decision],'pending-'+authority+'-'+decision)
  process_checks.append({'authority':authority,'expected':decision,'halt_exit':73,'fresh_process_check':True})
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__)]},'minecraft_jar_sha256':sha(minecraft),'process_crash_checks':process_checks,
 'limitations':['Synthetic player/world files; no native Player, startup/login hook, loaded world cache, DataFixer or game run.',
 'Historical catalog decodes existing ledger only. Current binding/catalog must match before new submission; config migration remains pending.',
 'Offline recovery requires caller exclusion of all writers and does not repair mismatched owner mirrors or authorize player admission.',
 'Partial first-time creation preserves all files and refuses to reopen; no automatic retry or reset to empty progress.',
 'No power-loss durability proof, external rollback/tail-deletion detection, malicious filesystem writer protection or symbolic-link execution test.']})
(out/'LegacyQuestWorldStoreCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

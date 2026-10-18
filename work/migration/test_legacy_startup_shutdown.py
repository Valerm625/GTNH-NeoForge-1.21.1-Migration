"""Failed-startup shutdown scope, unchanged files, real locks and pinned native call sites."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/startup-shutdown-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestPlayerAdmission','LegacyPlayerMarkerUpgrade','LegacyMaintenanceInitialization','LegacyPlayerNbtPatch','LegacyOwnerTransactionRecovery','LegacyRetrievalSaveScope']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyPlayerDataFileReaderCheck.java',R/'work/migration/LegacyQuestWorldStoreCheck.java',R/'work/migration/LegacyPlayerMarkerUpgradeCheck.java',R/'work/migration/LegacyStartupShutdownCheck.java']
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=90)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
# Old nested parser classes were replaced by the shared production helper.
for stale in (out/'org/gtnhmigration/compat').glob('LegacyPlayerMarkerUpgrade$RawMarkerNbt*.class'):
 assert stale.resolve().is_relative_to(out.resolve());stale.unlink()
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+checks],'compile')
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyStartupShutdownCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out))),relative(patched),relative(O/'mods/gtnh-migration-compat-0.1.0.jar')],'world-store')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))

report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__)]+[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/name for name in ['LegacyQuestNativeLifecycle.java','LegacyServerSaveScopeMixin.java','LegacyWorldFileSaveScopeMixin.java']]},
 'minecraft_jar_sha256':sha(minecraft),'patched_native_jar_sha256':sha(patched),'compat_jar_sha256':sha(O/'mods/gtnh-migration-compat-0.1.0.jar'),
 'limitations':['Executes production permit state machine, synthetic world/player/pending ledger, actual DirectoryLock and ledger lock. Server objects and shutdown calls are fixtures. Byte digests exclude the two locked lock files; their exclusion and reopen behavior are tested directly.',
 'Pinned native shutdown ordering and terminal runServer call sites are bytecode audited, with required isolated Mixin checks in a separate report. No Player, server loop, world or full NeoForge transformation chain executes.',
 'Only a recovery failure before original loadLevel body begins, an empty player/level set and terminal runServer stop can use this cleanup. Loaded-world failures and direct mod stop calls remain quarantined.',
 'Pending progress is never resolved and files are not saved. Resource/storage/ledger close errors retain process quarantine; no forced reset or automatic retry. External-mod resource behavior remains unverified.']})
(out/'LegacyStartupShutdownCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

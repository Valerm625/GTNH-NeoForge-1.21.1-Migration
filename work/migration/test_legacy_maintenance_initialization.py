"""Bound maintenance initialization, interrupted construction and fresh-process recovery."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/maintenance-initialization-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestPlayerAdmission','LegacyPlayerMarkerUpgrade','LegacyMaintenanceInitialization','LegacyPlayerNbtPatch','LegacyOwnerTransactionRecovery']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyPlayerDataFileReaderCheck.java',R/'work/migration/LegacyQuestWorldStoreCheck.java',R/'work/migration/LegacyPlayerMarkerUpgradeCheck.java',R/'work/migration/LegacyOwnerTransactionRecoveryCheck.java',R/'work/migration/LegacyMaintenanceInitializationCheck.java']
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
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyMaintenanceInitializationCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'world-store')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))

process_checks=[]
for kind in ['MARKER','OWNER']:
 for boundary in ['INTENT_TEMP_OPENED','INTENT_TEMP_FORCED','INTENT_PUBLISHED','ROOT_CREATED','BLOBS_CREATED','LEDGER_OPENED','GENESIS_TEMP_OPENED','GENESIS_TEMP_FORCED','GENESIS_PUBLISHED','COMPLETE_TEMP_OPENED','COMPLETE_TEMP_FORCED','COMPLETE_PUBLISHED']:
  directory=Path(tempfile.mkdtemp(prefix='restart-',dir=out))/'world'
  result=subprocess.run([str(java),'-cp','.;'+cp,'org.gtnhmigration.compat.LegacyMaintenanceInitializationCheck','crash',relative(directory),kind,boundary],cwd=out,capture_output=True,timeout=90)
  assert result.returncode==73,result.stdout+result.stderr
  log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyMaintenanceInitializationCheck','recover',relative(directory),kind], 'recover-'+kind+'-'+boundary)
  assert 'MAINTENANCE_INITIALIZATION_RESTART_OK' in log
  process_checks.append({'protocol':kind,'boundary':boundary,'halt_exit':73,'fresh_process_initialization_maintenance_and_admission':True})
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__)]},'minecraft_jar_sha256':sha(minecraft),'process_crash_checks':process_checks,
 'limitations':['Synthetic player/world inputs, actual file stores and exclusive locks; initialization preserves original files and every leftover temporary file, then runs maintenance/admission models. No Player, world or native game lifecycle runs.',
 'Only directories with a valid world/protocol construction intent and no completion or established operation can finish genesis. Completion evidence is outside the auxiliary root to detect a missing established directory.',
 'Legacy journals without new construction evidence remain reopen-only; ambiguous old partial initialization cannot be reset. Deletion/rollback of all independent evidence and power-loss directory persistence remain unproven.',
 'Main-world binding initialization, unknown source repair, arbitrary external writes and loaded-world failure shutdown remain separate incomplete requirements.']})
(out/'LegacyMaintenanceInitializationCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

"""World-authoritative transaction repair, binary preservation and process-halt recovery."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/owner-transaction-recovery-check';out.mkdir(parents=True,exist_ok=True)
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
checks=[R/'work/migration/LegacyPlayerDataFileReaderCheck.java',R/'work/migration/LegacyQuestWorldStoreCheck.java',R/'work/migration/LegacyPlayerMarkerUpgradeCheck.java',R/'work/migration/LegacyOwnerTransactionRecoveryCheck.java']
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
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyOwnerTransactionRecoveryCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'world-store')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))

process_checks=[]
for decision in ['COMMIT','ABORT']:
 for boundary in ['BLOBS_ARCHIVED','PREPARED','MIRROR_PUBLISHED','CACHE_UPDATED','MAIN_RESOLVED','RESOLVED']:
  directory=Path(tempfile.mkdtemp(prefix='restart-',dir=out))/'world'
  result=subprocess.run([str(java),'-cp','.;'+cp,'org.gtnhmigration.compat.LegacyOwnerTransactionRecoveryCheck','crash',relative(directory),decision,boundary],cwd=out,capture_output=True,timeout=90)
  assert result.returncode==73,result.stdout+result.stderr
  log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyOwnerTransactionRecoveryCheck','recover',relative(directory),decision,boundary],'recover-'+decision+'-'+boundary)
  assert 'OWNER_RESTART_ADMISSION_OK' in log
  process_checks.append({'decision':decision,'boundary':boundary,'halt_exit':73,'fresh_process_recovery_and_admission':True})
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__)]},'minecraft_jar_sha256':sha(minecraft),'process_crash_checks':process_checks,
 'limitations':['Synthetic world/player/cache files and real NBT/gzip, immutable archives, atomic mirror publication and main journal; no live Player or game lifecycle executes.',
 'The authoritative world file must match a pending WORLD_PLAYER transaction. Only mirrors and loaded caches proven as that same transaction before/after state are repaired; unknown/missing sources remain intact and blocked.',
 'No world-file or backup rewrite. Other mirror/cache fields are preserved. Complete before/after compressed evidence is archived; inventory and marker alone follow the world outcome.',
 'Native loaded NBT may already normalize negative zero. Targeted binary patching preserves unaffected file bytes, not arbitrary native-game round trips.',
 'Caller excludes live players, native saves and external writers. Machine power-loss durability, arbitrary-mod isolation and ambiguous legacy maintenance-directory initialization recovery remain unproven; new world-bound construction recovery has a separate fixture.']})
(out/'LegacyOwnerTransactionRecoveryCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

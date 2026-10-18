"""Native admission receipts, pinned call-site audit and save/load permit composition."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/quest-player-admission-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestPlayerAdmission','LegacyRetrievalSaveScope','LegacyRetrievalNativeSaveScope','LegacyQuestNativeLifecycle','LegacyPlayerMarkerUpgrade','LegacyPlayerNbtPatch','LegacyOwnerTransactionRecovery']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyPlayerDataFileReaderCheck.java',R/'work/migration/LegacyQuestWorldStoreCheck.java',R/'work/migration/LegacyQuestPlayerAdmissionCheck.java']
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
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestPlayerAdmissionCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'world-store')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))

process_checks=[]
for authority in ['PLAYER_FILE','WORLD_PLAYER']:
 for decision in ['COMMIT','ABORT']:
  directory=Path(tempfile.mkdtemp(prefix='restart-',dir=out))/'world'
  result=subprocess.run([str(java),'-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestWorldStoreCheck','pending-crash',relative(directory),authority+':'+decision],cwd=out,capture_output=True,timeout=60)
  assert result.returncode==73,result.stdout+result.stderr
  log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyQuestPlayerAdmissionCheck',authority,relative(directory)],'admission-'+authority+'-'+decision)
  assert 'FRESH_PROCESS_ADMISSION_OK' in log
  process_checks.append({'authority':authority,'previous_process_halt':73,'decision':decision,'fresh_process_receipt_after_recovery':True})

def inspect(classpath,name):
 result=subprocess.run([str(java.with_name('javap.exe')),'-classpath',str(classpath),'-c','-p',name],capture_output=True,timeout=30)
 assert result.returncode==0,name
 text=result.stdout.decode('utf-8',errors='replace').replace('\r','');(out/(name.rsplit('.',1)[-1]+'.javap.txt')).write_text(text,encoding='utf-8',newline='\n');return text

def method(text,name):
 found=re.search(r'\n  (?:public|private|protected) [^\n]*'+re.escape(name)+r'\([^\n]*\)[^\n]*;\n',text)
 assert found,name
 tail=text[found.end():];next_method=re.search(r'\n  \S',tail);return tail[:next_method.start()] if next_method else tail

def ordered(text,needles):
 position=0
 for needle in needles:
  index=text.find(needle,position);assert index>=0,needle;position=index+len(needle)

client=R/'work/cache/native-client-audit/neoforge-21.1.262-client.jar'
assert sha(client)=='aff67d0c3bbd015ecd881bb37416b740ada2e1e854433501ec5bb9381dbdd1c2'
dedicated=inspect(patched,'net.minecraft.server.dedicated.DedicatedServer')
integrated=inspect(client,'net.minecraft.client.server.IntegratedServer')
ordered(method(dedicated,'initServer'),['Method setPlayerList:','Method loadLevel:'])
assert 'Method setPlayerList:' in method(integrated,'net.minecraft.client.server.IntegratedServer')
assert 'Method loadLevel:' in method(integrated,'initServer')
lifecycle=inspect(out,'org.gtnhmigration.compat.LegacyQuestNativeLifecycle')
ordered(method(lifecycle,'beforeWorldLoad'),['LegacyRetrievalNativeSaveScope.beginServer:','Method hasMetadata:','LegacyQuestWorldStore.open:','LegacyPlayerMarkerUpgrade.resume:','LegacyOwnerTransactionRecovery.recover:','LegacyQuestPlayerAdmission.verifyPendingWorldCache:','LegacyQuestWorldStore.recoverOffline:','LegacyQuestPlayerAdmission.verifyResolvedWorldCache:'])
ordered(method(lifecycle,'load'),['LegacyRetrievalNativeSaveScope.begin:','LegacyPlayerMarkerUpgrade.promote:','LegacyQuestPlayerAdmission.begin:','LegacyRetrievalSaveScope.root:','LegacyQuestPlayerAdmission$Receipt.finish:','LegacyRetrievalSaveScope$Lease.release:'])
assert 'net/minecraft/client/' not in lifecycle
report['process_admission_checks']=process_checks
report['native_startup_order_audited']=True
report['compiled_admission_call_order_audited']=True
report['native_client_sha256']=sha(client)
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__)]},'minecraft_jar_sha256':sha(minecraft),'neoforge_patched_server_sha256':sha(patched),
 'limitations':['Synthetic NBT/files and production receipt/save-scope; no native Player, world, login or shutdown is executed.',
 'DataFixer and player loading events remain in pinned native bytecode; synthetic payload changes do not test actual DataFixer behavior.',
 'Native call-site registration is checked by the isolated Mixin report, not by this fixture execution.',
 'Legacy marker promotion has a separate durable maintenance fixture; mismatched owner inventory/cache repair, early failure cleanup and arbitrary other-mod inventory access remain incomplete.']})
(out/'LegacyQuestPlayerAdmissionCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

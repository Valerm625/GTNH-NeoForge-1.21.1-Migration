"""Transaction orchestration with actual inventory/NBT/files and native adapter compilation."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/retrieval-coordinator-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
extra=minecraft.with_name('server-1.21.1-20240808.144430-extra.jar')
patched=O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar'
universal=patched.with_name('neoforge-21.1.262-universal.jar')
assert hashlib.sha256(patched.read_bytes()).hexdigest()=='f57ce3018e21459d24b50508e8c1b4fd5c16324ec1dc5f316c192aa0130e7fe0'
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
cp=';'.join(relative(path) for path in [minecraft,extra]+libraries)
compile_cp=';'.join(relative(path) for path in [patched,universal])+ ';'+cp
names=['LegacyRetrievalEngine','LegacyQuestRetrievalPlanner','LegacyRetrievalInventoryBridge','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyRetrievalTransactionProposal','LegacyPlayerDataFileReader','LegacyRetrievalCommitCoordinator']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
check=R/'work/migration/LegacyRetrievalCommitCoordinatorCheck.java'
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=90)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+[check]],'compile')
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyRetrievalCommitCoordinatorCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'coordinator')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},'minecraft_jar_sha256':hashlib.sha256(minecraft.read_bytes()).hexdigest(),'neoforge_patched_server_sha256':hashlib.sha256(patched.read_bytes()).hexdigest(),
 'limitations':['Native ServerPlayer/PlayerDataStorage adapter compiles against the pinned NeoForge server but is not executed. Test runtime uses vanilla Inventory(null), NBT codecs, real files and a synthetic save port.',
 'Caller must implement continuous save/inventory/lifecycle exclusion and original authorization/party gates. Assertions and server-thread checks do not install a lifecycle barrier.',
 'This UUID.dat entry still refuses the integrated owner before persistence. The separate submitIntegratedOwner entry is compiled and covered by its own synthetic cache/mirror/world protocol checks; neither native entry is game-runtime verified.',
 'Failure quarantine blocks this store/inventory from another coordinator submission; it does not stop ordinary game ticks, saves, disconnects or admission. No hooks are registered.',
 'Only marker locations and actor count edits are changed; no full player reload, task/reward publication, UI synchronization or world import is implemented.',
 'Save failure scenarios are deterministic boundary injections, not a new JVM/power-loss test or live NeoForge callback test. Oak log predicates are fixtures, not a legacy logWood mapping.']})
(out/'LegacyRetrievalCommitCoordinatorCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

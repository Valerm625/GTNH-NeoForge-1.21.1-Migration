"""Independent ledger + synthetic player-file recovery, not native PlayerDataStorage."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/retrieval-transaction-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libs=[O/p for p in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
mc=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
def rel(p):return Path(os.path.relpath(p,out)).as_posix()
cp=';'.join(rel(p) for p in [mc]+libs)
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(n+'.java') for n in names]
check=R/'work/migration/LegacyRetrievalTransactionJournalCheck.java'
def run(executable,args,name,expected=0):
 def quote(v):
  assert not any(c in v for c in '\x00\r\n')
  return '"'+v.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in args)+'\n',encoding='utf-8',newline='\n')
 command=[str(executable)]+(args if executable.stem=='javap' else ['@'+argfile.name])
 p=subprocess.run(command,cwd=out,capture_output=True,timeout=60)
 log=(p.stdout+p.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert p.returncode==expected,name+' failed:\n'+log
 return log
server=O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar'
assert hashlib.sha256(server.read_bytes()).hexdigest()=='f57ce3018e21459d24b50508e8c1b4fd5c16324ec1dc5f316c192aa0130e7fe0'
disassembly=run(java.with_name('javap.exe'),['-classpath',rel(server),'-c','-p','net.minecraft.server.level.ServerPlayer'],'server-player-bytecode')
start=disassembly.index('  public void restoreFrom(net.minecraft.server.level.ServerPlayer, boolean);')
end=disassembly.find('\n  public ',start+3)
restore=disassembly[start:end]
instructions=[re.sub(r'\s+',' ',re.sub(r'#\d+','#POOL',m.group(1))).strip() for line in restore.splitlines() if (m:=re.match(r'\s*\d+:\s*(.*)',line))]
copy_start=next(i for i,line in enumerate(instructions) if '// Method getPersistentData:' in line)-1
scope=instructions[copy_start:copy_start+15]
expected=[
 'aload_1',
 'invokevirtual #POOL // Method getPersistentData:()Lnet/minecraft/nbt/CompoundTag;',
 'astore_3','aload_3','ldc_w #POOL // String PlayerPersisted',
 'invokevirtual #POOL // Method net/minecraft/nbt/CompoundTag.contains:(Ljava/lang/String;)Z',
 'ifeq 395','aload_0',
 'invokevirtual #POOL // Method getPersistentData:()Lnet/minecraft/nbt/CompoundTag;',
 'ldc_w #POOL // String PlayerPersisted','aload_3','ldc_w #POOL // String PlayerPersisted',
 'invokevirtual #POOL // Method net/minecraft/nbt/CompoundTag.get:(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;',
 'invokevirtual #POOL // Method net/minecraft/nbt/CompoundTag.put:(Ljava/lang/String;Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;','pop']
assert scope==expected,'Pinned native clone copy scope changed: '+repr(scope)
assert any('EventHooks.onPlayerClone:' in line for line in instructions[copy_start+15:]),'Clone event boundary missing'
with zipfile.ZipFile(server) as jar: clone_class_sha=hashlib.sha256(jar.read('net/minecraft/server/level/ServerPlayer.class')).hexdigest()
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',cp,'-d','.']+[rel(p) for p in sources+[check]],'compile')
prefix=['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyRetrievalTransactionJournalCheck']
fixture=Path(tempfile.mkdtemp(prefix='run-',dir=out))
log=run(java,prefix+['test',rel(fixture)],'journal')
report=json.loads(next(line for line in log.splitlines() if line.startswith('{')))
cuts=[]
for boundary in ['PREPARE_TEMP','PREPARED','PLAYER_TEMP','PLAYER_PUBLISHED','RESOLVE_TEMP','RESOLVE_PUBLISHED','RESOLVED']:
 base=fixture/boundary;run(java,prefix+['seed',rel(base)],'seed-'+boundary)
 original={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (base/'ledger').glob('*.nbt')}
 run(java,prefix+['crash',rel(base),boundary],'crash-'+boundary,72)
 player=hashlib.sha256((base/'player.dat').read_bytes()).hexdigest()
 committed=boundary in ['PLAYER_PUBLISHED','RESOLVE_TEMP','RESOLVE_PUBLISHED','RESOLVED']
 run(java,prefix+['recover',rel(base),str(committed).lower()],'recover-'+boundary)
 run(java,prefix+['recover',rel(base),str(committed).lower()],'replay-'+boundary)
 assert hashlib.sha256((base/'player.dat').read_bytes()).hexdigest()==player,'Recovery rewrote player data'
 for name,digest in original.items():assert hashlib.sha256((base/'ledger'/name).read_bytes()).hexdigest()==digest
 cuts.append({'boundary':boundary,'child_exit_code':72,'committed':committed,'player_file_unchanged_by_recovery':True,'prior_ledger_frames_retained':True,'second_recovery_idempotent':True})
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},'minecraft_jar_sha256':hashlib.sha256(mc.read_bytes()).hexdigest(),'process_crash_checks':cuts,
 'native_clone_verified':False,'persistent_marker_scope_fixture_verified':True,
 'clone_copy_scope_static_checks':{'patched_server_sha256':hashlib.sha256(server.read_bytes()).hexdigest(),'server_player_class_sha256':clone_class_sha,'method':'ServerPlayer.restoreFrom','persisted_key':'PlayerPersisted','instructions_verified':len(scope),'execution':'Bytecode inspected; copy scope simulated with CompoundTags, no native clone executed'},
 'limitations':['Synthetic full player NBT and a fixture atomic file writer, not native PlayerDataStorage or a world.',
 'The journal never writes player inventory; an actual coordinator must persist marker+inventory together, read back the authoritative file without dat_old fallback, and gate normal play until resolved.',
 'Accepted before/after player inventories and progress states must come from independently verified runtime snapshots and planning; this journal is not item matching, authorization or inventory validation.',
 'Aborting a pending transaction does not undo a live in-memory inventory; a future coordinator must restore/reload it before play resumes.',
 'Native death/clone/logout and event side effects remain unverified; the pinned PlayerPersisted copy scope and marker v1/v2 compatibility are checked with CompoundTag fixtures. Native save/readback, power loss, rewards, party and networking remain unverified.',
 'The existing append-only ledger retains source and transaction bytes; limits/compaction and manual removal of a complete history tail remain unresolved.']})
(out/'LegacyRetrievalTransactionJournalCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

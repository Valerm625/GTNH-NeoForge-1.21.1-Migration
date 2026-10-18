"""Transaction orchestration with actual inventory/NBT/files and native adapter compilation."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/integrated-owner-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyQuestRetrievalPlanner','LegacyRetrievalInventoryBridge','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyRetrievalTransactionProposal','LegacyPlayerDataFileReader','LegacyRetrievalCommitCoordinator','LegacyQuestWorldStore','LegacyRetrievalSaveScope','LegacyRetrievalNativeSaveScope']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
check=R/'work/migration/LegacyIntegratedOwnerCoordinatorCheck.java'
helper=R/'work/migration/LegacyRetrievalCommitCoordinatorCheck.java'
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=90)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+[helper,check]],'compile')
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyIntegratedOwnerCoordinatorCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'coordinator')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
# Reconstruct/check exact client processors in an isolated cache; never launch Minecraft.
subprocess.run([os.sys.executable,str(R/'work/migration/acquire_native_client_audit.py')],cwd=R,check=True,timeout=1500)
client_dir=R/'work/cache/native-client-audit'
lock=json.loads((R/'locks/native-client-audit-inputs.json').read_text(encoding='utf-8'))
client_patched=client_dir/'neoforge-21.1.262-client.jar';client_srg=client_dir/'client-srg.jar'
assert lock['cache_files_sha256'][client_patched.name]=='aff67d0c3bbd015ecd881bb37416b740ada2e1e854433501ec5bb9381dbdd1c2'

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inspect(classpath,name):
 result=subprocess.run([str(java.with_name('javap.exe')),'-classpath',classpath,'-c','-p',name],capture_output=True,timeout=30)
 assert result.returncode==0,name
 value=result.stdout.decode('utf-8',errors='replace').replace('\r','');(out/(name.rsplit('.',1)[-1]+'.javap.txt')).write_text(value,encoding='utf-8',newline='\n');return value

def method(text,signature):
 start=text.index('\n  '+signature+';')+1
 tail=text[start:];end=re.search(r'\n  \S',tail)
 return ' '.join((tail[:end.start()] if end else tail).split())

def ordered(text,needles):
 position=0
 for needle in needles:
  index=text.find(needle,position);assert index>=0,needle;position=index+len(needle)

client_cp=str(client_patched)+';'+str(client_srg)
owner='net.minecraft.client.server.IntegratedPlayerList';integrated='net.minecraft.client.server.IntegratedServer'
server='net.minecraft.server.MinecraftServer';players='net.minecraft.server.players.PlayerList'
texts={name:inspect(client_cp,name) for name in [owner,integrated,server,players]}
def audit(values):
 ordered(method(values[owner],'protected void save(net.minecraft.server.level.ServerPlayer)'),[
  'IntegratedServer.isSingleplayerOwner','ifeq 29','ServerPlayer.saveWithoutId','putfield','Field playerData:',
  'invokespecial','net/minecraft/server/players/PlayerList.save:'])
 ordered(method(values[owner],'public net.minecraft.nbt.CompoundTag getSingleplayerData()'),['getfield','Field playerData:','areturn'])
 ordered(method(values[players],'public void saveAll()'),['Field players:','get:','checkcast','Method save:'])
 ordered(method(values[server],'public boolean saveEverything(boolean, boolean, boolean)'),['Field isSaving:','PlayerList.saveAll:','Method saveAllChunks:','Field isSaving:'])
 ordered(method(values[server],'public boolean saveAllChunks(boolean, boolean, boolean)'),['PlayerList.getSingleplayerData:','LevelStorageSource$LevelStorageAccess.saveDataTag:'])
 ordered(method(values[integrated],'public void tickServer(java.util.function.BooleanSupplier)'),['Saving and pausing game','Method saveEverything:'])
 # The client override delegates to the base save and then checks disk space.
 ordered(method(values[integrated],'public boolean saveEverything(boolean, boolean, boolean)'),[
  'invokespecial','net/minecraft/server/MinecraftServer.saveEverything:','Method warnOnLowDiskSpace:','ireturn'])
 assert 'public boolean saveAllChunks(boolean, boolean, boolean);' not in values[integrated]

audit(texts)
negative_controls=0
for class_name,needle in [(owner,'putfield'),(owner,'net/minecraft/server/players/PlayerList.save:'),(server,'PlayerList.saveAll:'),(server,'PlayerList.getSingleplayerData:')]:
 changed=dict(texts);changed[class_name]=changed[class_name].replace(needle,'MUTATED_NATIVE_CONTRACT')
 try:audit(changed)
 except (AssertionError,ValueError):negative_controls+=1
 else:raise AssertionError('Native audit accepted missing '+needle)

native=inspect(str(out),'org.gtnhmigration.compat.LegacyRetrievalCommitCoordinator$NativeIntegratedOwnerPort')
ordered(method(native,'public void save()'),['Method assertExclusive:','LegacyRetrievalNativeSaveScope.saveOwner:'])
ordered(method(native,'void savePlayers()'),['Method assertExclusive:','LegacyRetrievalNativeSaveScope.savePlayers:'])
ordered(method(native,'net.minecraft.nbt.CompoundTag cachedPlayer() throws java.io.IOException'),['PlayerList.getSingleplayerData:','CompoundTag.copy:'])
assert 'net/minecraft/client/' not in native,'Owner adapter requires client-only class linking'
bridge=inspect(str(out),'org.gtnhmigration.compat.LegacyRetrievalNativeSaveScope')
assert 'LegacyRetrievalSaveScope.root:' in bridge and 'MinecraftServer.saveEverything:' in bridge and 'PlayerList.saveAll:' in bridge

class_hashes={}
import zipfile
for name in texts:
 for path in [client_patched,client_srg]:
  with zipfile.ZipFile(path) as z:
   entry=name.replace('.','/')+'.class'
   if entry in z.namelist():
    class_hashes[name]={'jar':path.name,'class_sha256':hashlib.sha256(z.read(entry)).hexdigest()};break
 else:raise AssertionError('Missing '+name)
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+[helper,check,Path(__file__),R/'work/migration/acquire_native_client_audit.py',R/'locks/native-client-audit-inputs.json']},
 'minecraft_jar_sha256':sha(minecraft),'neoforge_patched_server_sha256':sha(patched),
 'native_owner_save_audit':{'class_sha256':class_hashes,'neoforge_patched_client_sha256':sha(client_patched),'client_srg_sha256':sha(client_srg),
 'cache_capture_precedes_player_file_save':True,'world_save_reads_owner_cache':True,'integrated_save_delegates_then_checks_disk_space':True,'owner_adapter_saves_all_players_then_world':True,'abort_repairs_cache_and_mirror_without_world_save':True,'client_type_linking_required':False,'negative_controls':negative_controls,'native_code_executed':False},
 'limitations':['The compiled native owner adapter is not connected to a gameplay submit handler or executed; passive native guard mixins are registered. Tests use actual Inventory/ItemStack/NBT/ledger files and synthetic save boundaries, not native Player/world/client execution.',
 'The caller must continuously exclude all world/player saves, mutations and lifecycle changes on the entire server. The owner adapter calls saveEverything and abort repair may call PlayerList.saveAll, affecting all players and their native save callbacks.',
 'Only level.dat decides owner COMMIT/ABORT. Cache and UUID mirror must also agree before final resolution. Unknown mirrors/caches, failed repair or concurrent world metadata changes preserve pending intent and require isolated recovery.',
 'First world-player baseline creation, failed saves and later saves after abort are synthetic fixtures. No new JVM halt/power-loss validation, datafixer, backup fallback, login admission, role-switch recovery or EULA acceptance is claimed.',
 'The exact client and mappings are local audit inputs only. Their binaries/mappings and extracted native bytecode are not redistributed. Installer processor and input hashes are public provenance.',
 'Task rewards/completion bridge, team lifecycle, UI and logWood identity mapping remain unimplemented.']})
(out/'LegacyIntegratedOwnerCoordinatorCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'passed':True,'assertions':report['assertions'],'native_audit_negative_controls':negative_controls,'native_runtime_verified':False}))

"""Pinned native load authority audit and level.dat player journal recovery fixtures."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/world-player-authority-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyQuestRetrievalPlanner','LegacyRetrievalInventoryBridge','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyRetrievalTransactionProposal','LegacyPlayerDataFileReader','LegacyRetrievalCommitCoordinator']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyPlayerDataFileReaderCheck.java',R/'work/migration/LegacyWorldPlayerAuthorityCheck.java']
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
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyWorldPlayerAuthorityCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'world-authority')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))

def inspect(classpath,name):
 result=subprocess.run([str(java.with_name('javap.exe')),'-classpath',str(classpath),'-c','-p',name],capture_output=True,timeout=30)
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

classes=['net.minecraft.server.players.PlayerList','net.minecraft.world.entity.player.Player','net.minecraft.server.MinecraftServer','net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess','net.minecraft.world.level.storage.PrimaryLevelData']
bytecode={name:inspect(patched,name) for name in classes}
player_list=bytecode[classes[0]]
ordered(method(player_list,'public java.util.Optional<net.minecraft.nbt.CompoundTag> load(net.minecraft.server.level.ServerPlayer)'),[
 'WorldData.getLoadedPlayerTag','MinecraftServer.isSingleplayerOwner','ifeq 70','ifnull 70','ServerPlayer.load:',
 'EventHooks.firePlayerLoadingEvent','goto 79','PlayerDataStorage.load:'])
ordered(method(player_list,'public void placeNewPlayer(net.minecraft.network.Connection, net.minecraft.server.level.ServerPlayer, net.minecraft.server.network.CommonListenerCookie)'),[
 'Method load:(Lnet/minecraft/server/level/ServerPlayer;)','Field players:','EventHooks.firePlayerLoggedIn'])
ordered(method(player_list,'public void remove(net.minecraft.server.level.ServerPlayer)'),[
 'EventHooks.firePlayerLoggedOut','Method save:(Lnet/minecraft/server/level/ServerPlayer;)','ServerLevel.removePlayerImmediately'])
ordered(method(bytecode[classes[1]],'public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag)'),[
 'NbtUtils.addCurrentDataVersion','Inventory.save:'])
ordered(method(bytecode[classes[2]],'public boolean saveAllChunks(boolean, boolean, boolean)'),[
 'PlayerList.getSingleplayerData','LevelStorageSource$LevelStorageAccess.saveDataTag'])
ordered(method(bytecode[classes[3]],'public void saveDataTag(net.minecraft.core.RegistryAccess, net.minecraft.world.level.storage.WorldData, net.minecraft.nbt.CompoundTag)'),[
 'WorldData.createTag','String Data','CommonHooks.writeAdditionalLevelSaveData','Method saveLevelData'])
ordered(method(bytecode[classes[4]],'private void setTagData(net.minecraft.core.RegistryAccess, net.minecraft.nbt.CompoundTag, net.minecraft.nbt.CompoundTag)'),[
 'String Player','CompoundTag.put:'])
assert 'Field loadedPlayerTag:' in method(bytecode[classes[4]],'public net.minecraft.nbt.CompoundTag getLoadedPlayerTag()')
native_name='org.gtnhmigration.compat.LegacyRetrievalCommitCoordinator$NativePlayerPort'
native=inspect(out,native_name)
ordered(method(native,'public void assertExclusive()'),['MinecraftServer.isSingleplayerOwner','Integrated owner requires level.dat','java/lang/Runnable.run'])
with zipfile.ZipFile(patched) as z:
 class_hashes={name:hashlib.sha256(z.read(name.replace('.','/')+'.class')).hexdigest() for name in classes}
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__)]},'minecraft_jar_sha256':sha(minecraft),'neoforge_patched_server_sha256':sha(patched),
 'native_authority_audit':{'class_sha256':class_hashes,'integrated_owner_loaded_tag_precedes_uuid_file':True,'player_load_precedes_player_list_add_and_login_event':True,'logout_event_then_save_precedes_removal':True,'player_save_adds_current_data_version':True,'world_save_wraps_player_in_data_player':True,'compiled_uuid_only_native_adapter_rejects_integrated_owner':True,'native_code_executed':False},
 'limitations':['PlayerList authority branches and world-save structure are checked in pinned native bytecode; no native class methods, world, login, logout or clone events execute.',
 'Synthetic level.dat/playerdata files and actual NBT/journal recovery prove source binding, not integrated-server save/cache synchronization.',
 'The current native submission adapter explicitly rejects the integrated owner before persistence. Full support still needs its player cache, level.dat/world save and lifecycle exclusion; this is a tracked gap, not removal from the goal.',
 'Explicit WORLD_PLAYER preparation is schema 2; existing UUID.dat schema 1 source records remain unchanged. The source archive namespace and resolution-record schema remain v1.',
 'Both level.dat and UUID.dat readers retain whole compressed documents; no level.dat_old or mirror fallback, no DataFixer, metadata write or real player-save fixture.',
 'Legacy bare-NBT resolve is limited to PLAYER_FILE transactions. Source-bound snapshots verify their full document digest before resolution; callers must still exclude other writers.']})
(out/'LegacyWorldPlayerAuthorityCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

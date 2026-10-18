"""Execute pinned BQ passive-completion bodies against the new immutable planner."""
from pathlib import Path
import hashlib,json,os,re,subprocess
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/quest-completion-planner-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestDefinitionArchive','LegacyTypedNbtJsonReader','LegacyRetrievalDefinitionBinding','LegacyRetrievalInventoryBridge','LegacyQuestRetrievalPlanner','LegacyRetrievalStackMatcher','LegacyQuestEntryPolicy','LegacyQuestCompletionPlanner','LegacyRetrievalTransactionProposal']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyQuestCompletionPlannerCheck.java']
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=120)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
upstream=R/'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java'
pins={'betterquesting/questing/QuestInstance.java':'723ed08931294710f7e287eccc7f6c042c9ff4bf5dd500183522758b4726bffb','betterquesting/api/enums/EnumLogic.java':'ac76e9f5caf672c39252c7585ff5b53da648078eca7ffebdeac70e1cd54c2c76','betterquesting/api2/storage/UuidDatabase.java':'42569b7a9c66300873578ed2d857be0684938b72e2ead3e1b4fb1a8e5da8343a','betterquesting/handlers/EventHandler.java':'71e4f78dbb08f8ca33cdec09ce7b73dca8fb8f1341d24186f3906cd569e38202','betterquesting/api2/cache/QuestCache.java':'27d77bd35887b3574cbd1b01b4c5f465e7d21d5a7cc9c680caa52bad5fe6e9cd','betterquesting/api/properties/NativeProps.java':'45e34d39d56d82e43e9a8054909954252832b1a232e5c337749cadf68f5eae8b'}
for name,expected in pins.items():assert sha(upstream/name)==expected,name
def source(name):return (upstream/name).read_text(encoding='utf-8')
def method(text,start):
 start=text.index(start);opening=text.index('{',start);depth=1;end=opening+1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
quest=source('betterquesting/questing/QuestInstance.java')
methods='\n'.join(method(quest,name) for name in ['public void update(EntityPlayer player)','public void setComplete(UUID uuid, long timestamp)','public NBTTagCompound writeProgressToNBT(NBTTagCompound json, @Nullable List<UUID> users)','public boolean canSubmit(EntityPlayer player)','public boolean isUnlocked(UUID uuid)','public boolean isComplete(UUID uuid)','public NBTTagCompound getCompletionInfo(UUID uuid)'])
event=source('betterquesting/handlers/EventHandler.java')
# Preserve the complete early-return and passive pass source text verbatim;
# later repeat/reset/auto-claim/sync phases are explicitly outside this oracle.
passive=event[event.index('public void onLivingUpdate(BQLivingUpdateEvent event)'):event.index('        // Repeatable quest resets')]+"    }\n"
cache=method(source('betterquesting/api2/cache/QuestCache.java'),'public synchronized void updateCache(@Nonnull EntityPlayer player)')
get_all=method(source('betterquesting/api2/storage/UuidDatabase.java'),'public Stream<T> getAll(Collection<UUID> keys)')
oracle=out/'BqQuestCompletionReference.java'
prefix='''package org.gtnhmigration.compat;
import java.util.*;import java.util.Map.Entry;import java.util.stream.*;import java.util.function.LongSupplier;import net.minecraft.nbt.*;import betterquesting.api.enums.EnumLogic;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
public final class BqQuestCompletionReference {
 @interface Nonnull {} @interface Nullable {}
 static LongSupplier CLOCK;
 static final class System {static long currentTimeMillis(){return CLOCK.getAsLong();}}
 static final List<UUID> UPDATED=new ArrayList<>(),COMPLETED=new ArrayList<>(),NOTICES=new ArrayList<>();
 static final Set<UUID> DIRTY_PLAYERS=new HashSet<>();static boolean POSTED;
 static final class Property<T> {final T fallback;Property(T value){fallback=value;}}
 static final class NativeProps {
  static final Property<Boolean> GLOBAL=new Property<>(false),SIMULTANEOUS=new Property<>(false),LOCKED_PROGRESS=new Property<>(false),AUTO_CLAIM=new Property<>(false),REPEAT_REL=new Property<>(true),SILENT=new Property<>(false),EDIT_MODE=new Property<>(false);
  static final Property<Integer> REPEAT_TIME=new Property<>(-1);
  static final Property<EnumLogic> LOGIC_QUEST=new Property<>(EnumLogic.AND),LOGIC_TASK=new Property<>(EnumLogic.AND);
 }
 static final class Info {final Map<Property<?>,Object> values=new HashMap<>();@SuppressWarnings("unchecked") <T>T getProperty(Property<T> p){return (T)values.getOrDefault(p,p.fallback);}}
 static final class QuestSettings {static final Info INSTANCE=new Info();}
 static class EntityPlayer {final UUID id;QuestCache cache;EntityPlayer(UUID id){this.id=id;}Object getExtendedProperties(String id){return cache;}}
 static final class EntityPlayerMP extends EntityPlayer {int ticksExisted;World worldObj=new World();EntityPlayerMP(UUID id){super(id);}}
 static final class World {boolean isRemote;}
 static final class BQLivingUpdateEvent {final EntityPlayerMP entityLiving;BQLivingUpdateEvent(EntityPlayerMP player){entityLiving=player;}}
 static final class QuestingAPI {static UUID getQuestingUUID(EntityPlayer player){return player.id;}static Database<IQuest> getAPI(Object ref){return QuestDatabase.INSTANCE;}}
 static final class ApiReference {static final Object QUEST_DB=new Object();}
 static final class NBTTagList {final ListTag value=new ListTag();void appendTag(NBTTagCompound tag){value.add(tag.value.copy());}}
 static final class NBTTagCompound {final CompoundTag value;NBTTagCompound(){value=new CompoundTag();}NBTTagCompound(CompoundTag value){this.value=value.copy();}boolean getBoolean(String key){return value.getBoolean(key);}long getLong(String key){return value.getLong(key);}NBTTagCompound copy(){return new NBTTagCompound(value);}void setTag(String key,NBTTagList list){value.put(key,list.value);}void setString(String key,String v){value.putString(key,v);}void setBoolean(String key,boolean v){value.putBoolean(key,v);}void setLong(String key,long v){value.putLong(key,v);}}
 static final class ITask {final Set<UUID> completed=new HashSet<>();boolean isComplete(UUID id){return completed.contains(id);}boolean ignored(UUID id){return false;}void setComplete(UUID id){completed.add(id);}}
 record DBEntry<T>(T value){T getValue(){return value;}}
 static final class Tasks {final List<DBEntry<ITask>> entries=new ArrayList<>();NBTTagList writeProgressToNBT(NBTTagList list,List<UUID> users){return list;}int size(){return entries.size();}List<DBEntry<ITask>> getEntries(){return entries;}}
 static final class DirtyPlayerMarker {static void markDirty(UUID id){DIRTY_PLAYERS.add(id);}}
 static class Database<T> {final Map<UUID,T> database=new LinkedHashMap<>();Set<Map.Entry<UUID,T>> entrySet(){return database.entrySet();}
  // The fixture injects the iteration order after the native filterKeys result;
  // it does not assert that source HashMap order is stable across versions.
  Map<UUID,T> filterKeys(Collection<UUID> keys){Map<UUID,T> out=new LinkedHashMap<>();for(UUID id:keys)if(database.containsKey(id))out.put(id,database.get(id));return out;}
'''
middle='''
 }
 static final class QuestDatabase extends Database<IQuest> {static final QuestDatabase INSTANCE=new QuestDatabase();}
 static class QuestInstance {
  final UUID id;QuestInstance(UUID id){this.id=id;}
  final Info qInfo=new Info();final Tasks tasks=new Tasks();final Set<UUID> preRequisites=new HashSet<>();final Map<UUID,NBTTagCompound> completeUsers=new HashMap<>();
  <T>T getProperty(Property<T> p){return qInfo.getProperty(p);}Tasks getTasks(){return tasks;}
  boolean hasClaimed(UUID id){return false;}boolean canClaimBasically(EntityPlayer player){return false;}
  void resetUser(UUID id,boolean full){throw new AssertionError("Unsupported simultaneous/repeat branch");}
'''
cache_prefix='''
 }
 static final class IQuest extends QuestInstance {IQuest(UUID id){super(id);}@Override public void update(EntityPlayer player){UPDATED.add(id);super.update(player);}}
 static final class QResetTime {QResetTime(UUID id,long time){}}
 static final class QuestCache {
  static final String LOC_QUEST_CACHE="cache";final Set<UUID> visibleQuests=new LinkedHashSet<>(),activeQuests=new LinkedHashSet<>(),autoClaims=new HashSet<>(),markedDirty=new HashSet<>();final List<QResetTime> resetSchedule=new ArrayList<>();
  Set<UUID> getActiveQuests(){return activeQuests;}Set<UUID> getPendingAutoClaims(){return autoClaims;}QResetTime[] getScheduledResets(){return new QResetTime[0];}
  void markQuestDirty(UUID id){markedDirty.add(id);}boolean isQuestShown(IQuest q,UUID id,EntityPlayer player){return false;}
'''
event_prefix='''
 }
 static final class BetterQuesting {static boolean isVmLoaded=false;}
 static final class VmAdapter {static void addBqConditions(UUID id,List<UUID> list){}static void sendPlayerSatisfiedCache(){}}
 static final class NetCacheSync {static void sendSync(EntityPlayerMP player){}}
 enum Type {COMPLETED}
 record QuestEvent(Type type,UUID actor,List<UUID> quests){}
 static final class Bus {void post(QuestEvent event){POSTED=true;COMPLETED.addAll(event.quests());}}
 static final class MinecraftForge {static final Bus EVENT_BUS=new Bus();}
 static void postPresetNotice(IQuest quest,EntityPlayer player,int type){NOTICES.add(quest.id);}
'''
ending='''
 public record Result(State after,List<UUID> updated,List<UUID> completed,List<UUID> notices,Set<UUID> dirtyPlayers,Set<UUID> dirtyQuests,boolean posted){}
 public static Result run(List<CompoundTag> definitions,State before,UUID actor,List<UUID> active,LegacyQuestCompletionPlanner.Tick tick,LongSupplier clock){
  install(definitions,before);CLOCK=clock;UPDATED.clear();COMPLETED.clear();NOTICES.clear();DIRTY_PLAYERS.clear();POSTED=false;
  EntityPlayerMP player=new EntityPlayerMP(actor);player.ticksExisted=tick.ticksExisted();player.worldObj.isRemote=tick.clientSide();player.cache=tick.cachePresent()?new QuestCache():null;
  if(player.cache!=null)player.cache.activeQuests.addAll(active);QuestSettings.INSTANCE.values.put(NativeProps.EDIT_MODE,tick.editMode());
  new BqQuestCompletionReference().onLivingUpdate(new BQLivingUpdateEvent(player));
  var originalRows=before.questCompletions();Map<UUID,Map<UUID,CompoundTag>> records=new LinkedHashMap<>(originalRows);
  QuestDatabase.INSTANCE.database.forEach((id,q)->{if(!q.completeUsers.isEmpty() || records.containsKey(id)){Map<UUID,CompoundTag> rows=new LinkedHashMap<>();q.completeUsers.forEach((uuid,tag)->rows.put(uuid,tag.value.copy()));for(Tag tag:q.writeProgressToNBT(new NBTTagCompound(),null).value.getList("completed",10)){CompoundTag row=(CompoundTag)tag;UUID uuid=UUID.fromString(row.getString("uuid"));if(!originalRows.getOrDefault(id,Map.of()).containsKey(uuid))rows.put(uuid,row);}records.put(id,rows);}});
  return new Result(new State(records,before.tasks()),List.copyOf(UPDATED),List.copyOf(COMPLETED),List.copyOf(NOTICES),Set.copyOf(DIRTY_PLAYERS),player.cache==null?Set.of():Set.copyOf(player.cache.markedDirty),POSTED);
 }
 public static Set<UUID> active(List<CompoundTag> definitions,State state,UUID actor,List<UUID> subset){install(definitions,state);CLOCK=()->123L;EntityPlayerMP player=new EntityPlayerMP(actor);player.cache=new QuestCache();player.cache.updateCache(player);Set<UUID> result=new HashSet<>(player.cache.activeQuests);result.retainAll(subset);return result;}
 static void install(List<CompoundTag> definitions,State before){
  QuestDatabase.INSTANCE.database.clear();var completionRows=before.questCompletions();
  for(CompoundTag raw:definitions){UUID id=new UUID(raw.getLong("questIDHigh"),raw.getLong("questIDLow"));IQuest q=new IQuest(id);CompoundTag p=raw.getCompound("properties").getCompound("betterquesting");
   q.qInfo.values.put(NativeProps.GLOBAL,p.getBoolean("isGlobal"));q.qInfo.values.put(NativeProps.SILENT,p.getBoolean("isSilent"));q.qInfo.values.put(NativeProps.LOCKED_PROGRESS,p.getBoolean("lockedProgress"));q.qInfo.values.put(NativeProps.REPEAT_TIME,p.contains("repeatTime")?p.getInt("repeatTime"):-1);
   q.qInfo.values.put(NativeProps.LOGIC_QUEST,p.contains("questLogic")?EnumLogic.valueOf(p.getString("questLogic")):EnumLogic.AND);q.qInfo.values.put(NativeProps.LOGIC_TASK,p.contains("taskLogic")?EnumLogic.valueOf(p.getString("taskLogic")):EnumLogic.AND);q.qInfo.values.put(NativeProps.SIMULTANEOUS,p.getBoolean("simultaneous"));
   for(Tag t:raw.getList("preRequisites",10)){CompoundTag ref=(CompoundTag)t;q.preRequisites.add(new UUID(ref.getLong("questIDHigh"),ref.getLong("questIDLow")));}
   for(Tag t:raw.getList("tasks",10)){CompoundTag task=(CompoundTag)t;ITask it=new ITask();var key=new TaskKey(id,task.getInt("index"),task.getString("taskID"));it.completed.addAll(before.tasks().getOrDefault(key,LegacyRetrievalEngine.State.empty()).completed());q.tasks.entries.add(new DBEntry<>(it));}
   completionRows.getOrDefault(id,Map.of()).forEach((uuid,tag)->q.completeUsers.put(uuid,new NBTTagCompound(tag)));QuestDatabase.INSTANCE.database.put(id,q);
  }
 }
}
'''
oracle.write_text(prefix+get_all+middle+methods+cache_prefix+cache+event_prefix+passive+ending,encoding='utf-8',newline='\n')
logic=upstream/'betterquesting/api/enums/EnumLogic.java'
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+checks+[oracle,logic]],'compile')
pin=R/'work/compat-mod/src/main/resources/gtnh_migration/quest-definitions.pin'
log=run(java,['-Xmx1g','-cp','.;'+relative(pin.parent.parent)+';'+cp,'org.gtnhmigration.compat.LegacyQuestCompletionPlannerCheck',relative(O)],'completion-planner')
report=json.loads(next(line[line.index('{'):] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__),pin]},'minecraft_jar_sha256':sha(minecraft),'archive_sha256':pin.read_text(encoding='ascii').splitlines()[1],'upstream_source_sha256':pins,'oracle_sha256':sha(oracle),'source_methods_executed':['EventHandler.onLivingUpdate early returns and passive completion block (verbatim prefix)','QuestCache.updateCache','QuestInstance.update','QuestInstance.setComplete','QuestInstance.writeProgressToNBT (new completion rows, task serialization adapter)','QuestInstance.isUnlocked','QuestInstance.isComplete','QuestInstance.canSubmit (nonrepeat branch)','QuestInstance.getCompletionInfo','UuidDatabase.getAll','EnumLogic.getResult'],
 'limitations':['Oracle uses player, property, cache, task-latch, event, NBT and clock adapters; no legacy or modern game world is executed.',
 'Iteration order is explicitly supplied after source filterKeys; source HashMap order is not replaced with a claimed stable sort.',
 'Existing opaque completion rows compare to source memory after update. Only newly created rows use source serialization for ledger routing; this does not rewrite or export an entire BQ save.',
 'Only whole bound nonrepeat AND retrieval declarations are accepted. Repeats, simultaneous, global and ignored/unknown task completion remain unavailable.',
 'Plans and active-membership projections are not a full quest cache, native party authorization, durable commit endpoint or dispatched events/rewards/UI.',
 'Inventory-to-ledger composition uses null-owner Inventory and synthetic files. No actual ServerPlayer, login or world completion is verified.']})
(out/'LegacyQuestCompletionPlannerCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

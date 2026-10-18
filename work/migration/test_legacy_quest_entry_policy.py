"""Compare source prerequisite/completion gates and compose with real definition/inventory models."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/quest-entry-policy-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestDefinitionArchive','LegacyTypedNbtJsonReader','LegacyRetrievalDefinitionBinding','LegacyRetrievalInventoryBridge','LegacyQuestRetrievalPlanner','LegacyRetrievalStackMatcher','LegacyQuestEntryPolicy']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyQuestEntryPolicyCheck.java']

def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=90)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
upstream=R/'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java'
pins={'betterquesting/questing/QuestInstance.java':'723ed08931294710f7e287eccc7f6c042c9ff4bf5dd500183522758b4726bffb','betterquesting/api/enums/EnumLogic.java':'ac76e9f5caf672c39252c7585ff5b53da648078eca7ffebdeac70e1cd54c2c76','betterquesting/api2/storage/UuidDatabase.java':'42569b7a9c66300873578ed2d857be0684938b72e2ead3e1b4fb1a8e5da8343a','betterquesting/api/questing/IQuest.java':'473b71ef6840955d8c15e3a96f94178403312e9164a2097784e71485ada4a7df'}
for name,expected in pins.items():assert sha(upstream/name)==expected,name
quest=(upstream/'betterquesting/questing/QuestInstance.java').read_text(encoding='utf-8')
database=(upstream/'betterquesting/api2/storage/UuidDatabase.java').read_text(encoding='utf-8')
def method(text,start):
 start=text.index(start);opening=text.index('{',start);depth=1;end=opening+1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
methods='\n'.join(method(quest,name) for name in ['public boolean canSubmit(EntityPlayer player)','public boolean isUnlocked(UUID uuid)','public boolean isComplete(UUID uuid)','public NBTTagCompound getCompletionInfo(UUID uuid)'])
get_all=method(database,'public Stream<T> getAll(Collection<UUID> keys)')
oracle=out/'BqQuestEntryReference.java'
prefix="""package org.gtnhmigration.compat;
import java.util.*;import java.util.stream.*;import net.minecraft.nbt.*;import betterquesting.api.enums.EnumLogic;
public final class BqQuestEntryReference {
 static final class Property<T> {final T fallback;Property(T fallback){this.fallback=fallback;}}
 static final class NativeProps {static final Property<Boolean> GLOBAL=new Property<>(false);static final Property<Integer> REPEAT_TIME=new Property<>(-1);static final Property<EnumLogic> LOGIC_QUEST=new Property<>(EnumLogic.AND),LOGIC_TASK=new Property<>(EnumLogic.AND);}
 static final class Info {final Map<Property<?>,Object> values=new HashMap<>();@SuppressWarnings("unchecked") <T>T getProperty(Property<T> p){return (T)values.getOrDefault(p,p.fallback);}}
 public static final class EntityPlayer {final UUID id;public EntityPlayer(UUID id){this.id=id;}}
 static final class QuestingAPI {static UUID getQuestingUUID(EntityPlayer player){return player.id;}}
 static final class NBTTagCompound {final CompoundTag value;NBTTagCompound(CompoundTag value){this.value=value.copy();}boolean getBoolean(String key){return value.getBoolean(key);}}
 interface ITask {boolean isComplete(UUID id);boolean ignored(UUID id);}
 record DBEntry<T>(T value){T getValue(){return value;}}
 static final class Tasks {int size(){return 0;}List<DBEntry<ITask>> getEntries(){return List.of();}}
 static class Database<T> {final Map<UUID,T> database=new HashMap<>();
"""
suffix="""
 }
 static final class QuestDatabase extends Database<Quest> {static final QuestDatabase INSTANCE=new QuestDatabase();}
 public static final class Quest {
  final Info qInfo=new Info();final Set<UUID> preRequisites=new HashSet<>();final Map<UUID,NBTTagCompound> completeUsers=new HashMap<>();final Tasks tasks=new Tasks();
  <T>T getProperty(Property<T> p){return qInfo.getProperty(p);}
"""
ending="""
 }
 public static Quest quest(UUID id){return QuestDatabase.INSTANCE.database.get(id);}
 public static void install(List<CompoundTag> definitions,Map<UUID,Map<UUID,CompoundTag>> completions){
  QuestDatabase.INSTANCE.database.clear();
  for(CompoundTag raw:definitions){UUID id=new UUID(raw.getLong("questIDHigh"),raw.getLong("questIDLow"));Quest q=new Quest();CompoundTag props=raw.getCompound("properties").getCompound("betterquesting");
   q.qInfo.values.put(NativeProps.GLOBAL,props.getBoolean("isGlobal"));q.qInfo.values.put(NativeProps.REPEAT_TIME,props.contains("repeatTime")?props.getInt("repeatTime"):-1);q.qInfo.values.put(NativeProps.LOGIC_QUEST,props.contains("questLogic")?EnumLogic.valueOf(props.getString("questLogic")):EnumLogic.AND);
   for(Tag tag:raw.getList("preRequisites",10)){CompoundTag ref=(CompoundTag)tag;q.preRequisites.add(new UUID(ref.getLong("questIDHigh"),ref.getLong("questIDLow")));}
   completions.getOrDefault(id,Map.of()).forEach((actor,tag)->q.completeUsers.put(actor,new NBTTagCompound(tag)));QuestDatabase.INSTANCE.database.put(id,q);
  }
 }
}
"""
oracle.write_text(prefix+get_all+suffix+methods+ending,encoding='utf-8',newline='\n')
logic=upstream/'betterquesting/api/enums/EnumLogic.java'
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+checks+[oracle,logic]],'compile')
pin=R/'work/compat-mod/src/main/resources/gtnh_migration/quest-definitions.pin'
log=run(java,['-Xmx1g','-cp','.;'+relative(pin.parent.parent)+';'+cp,'org.gtnhmigration.compat.LegacyQuestEntryPolicyCheck',relative(O)],'entry-policy')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__),pin]},'minecraft_jar_sha256':sha(minecraft),'archive_sha256':pin.read_text(encoding='ascii').splitlines()[1],'upstream_source_sha256':pins,'oracle_sha256':sha(oracle),'source_methods_executed':['QuestInstance.isUnlocked','QuestInstance.isComplete','QuestInstance.canSubmit (nonrepeat branch)','QuestInstance.getCompletionInfo','UuidDatabase.getAll','EnumLogic.getResult'],
 'limitations':['Source methods execute with player/property/storage adapters and actual modern NBT. Repeat-task canSubmit is deliberately not verified.',
 'Actual source gates compose with real vanilla inventory objects; cache presence, participants and authorization are fixtures, not native game execution.',
 'Native entry query is compiled/audited only; no party adapter, quest completion/rewards, first-time world import or submission endpoint is installed.',
 'Snapshot head is for stale-plan checking, not an authorization token. Three missing source prerequisites are retained without changing source unlock semantics.']})
(out/'LegacyQuestEntryPolicyCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

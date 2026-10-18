"""Bind actual source retrieval definitions to vanilla registry items and inventory plans."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/retrieval-definition-binding-check';out.mkdir(parents=True,exist_ok=True)
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
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestDefinitionArchive','LegacyTypedNbtJsonReader','LegacyRetrievalDefinitionBinding','LegacyRetrievalInventoryBridge','LegacyQuestRetrievalPlanner','LegacyRetrievalStackMatcher']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyRetrievalDefinitionBindingCheck.java']

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
pins={"bq_standard/tasks/TaskRetrieval.java":"8ac57331877e643a95c5c5d39966cce8a3d854d27319df80bee8514d19a22f71","betterquesting/api/utils/ItemComparison.java":"d3b2359c62c88ccab2f7e5fcf9430076b3904902b55c2aee81cebc67a1866b66","betterquesting/api/utils/JsonHelper.java":"ffc446be0c256b3fd2a6b770461466e2332821b6ce33a2d5487589cf0327f8d0","betterquesting/api/properties/NativeProps.java":"45e34d39d56d82e43e9a8054909954252832b1a232e5c337749cadf68f5eae8b","betterquesting/questing/QuestInstance.java":"723ed08931294710f7e287eccc7f6c042c9ff4bf5dd500183522758b4726bffb","betterquesting/api/questing/tasks/ITask.java":"55d37cf205fef879eb2a84986ee2212337b4933e328d3f8a16ef6843fd491ef7"}
for name,digest in pins.items():assert sha(upstream/name)==digest,name
source=(upstream/'bq_standard/tasks/TaskRetrieval.java').read_text(encoding='utf-8')
start=source.index('public void readFromNBT(NBTTagCompound nbt)');opening=source.index('{',start);depth=1;end=opening+1
while depth:
 depth+=(source[end]=='{')-(source[end]=='}');end+=1
method=source[start:end]
oracle=out/'BqTaskDefinitionReadReference.java'
oracle.write_text('package org.gtnhmigration.compat;\nimport java.util.*;\nimport net.minecraft.nbt.*;\npublic final class BqTaskDefinitionReadReference {\npublic boolean partialMatch=true,ignoreNBT=true,consume=false,groupDetect=false,autoConsume=false,requireOnlyOneItem=false;\npublic final List<CompoundTag> requiredItems=new ArrayList<>();\n'+method+\
 '\nstatic final class Constants {static final class NBT {static final int TAG_COMPOUND=10;}}\nstatic final class NBTTagCompound {final CompoundTag value;NBTTagCompound(CompoundTag value){this.value=value.copy();}boolean getBoolean(String key){return value.getBoolean(key);}NBTTagList getTagList(String key,int type){return new NBTTagList(value.getList(key,type));}}\nstatic final class NBTTagList {final ListTag value;NBTTagList(ListTag value){this.value=value;}int tagCount(){return value.size();}NBTTagCompound getCompoundTagAt(int index){return new NBTTagCompound(value.getCompound(index));}}\nstatic final class JsonHelper {static CompoundTag JsonToItemStack(NBTTagCompound tag){return tag.value.copy();}}\n}\n',encoding='utf-8',newline='\n')
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+checks+[oracle]],'compile')
pin=R/'work/compat-mod/src/main/resources/gtnh_migration/quest-definitions.pin'
log=run(java,['-Xmx1g','-cp','.;'+relative(pin.parent.parent)+';'+cp,'org.gtnhmigration.compat.LegacyRetrievalDefinitionBindingCheck',relative(O)],'binding')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__),pin]},'minecraft_jar_sha256':sha(minecraft),'archive_sha256':pin.read_text(encoding='ascii').splitlines()[1],'upstream_source_sha256':pins,'oracle_sha256':sha(oracle),'source_readFromNBT_executed':True,
 'limitations':['Actual vanilla registry, ItemStack and null-owner Inventory objects; no native player/world or mod registry execution.',
 'Bound declarations cover manual task detection only. Gate and party authorization are fixtures; no quest completion, reward, UI or native inventory persistence.',
 'Source readFromNBT executes unchanged with modern NBT/list adapters; JsonToItemStack is a raw-compound adapter, not the old registry or placeholder implementation.',
 'Nonempty compared NBT, unresolved ore unions, unsupported modes and missing registry targets reject the whole declaration; raw definitions/settings remain retained.']})
(out/'LegacyRetrievalDefinitionBindingCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))

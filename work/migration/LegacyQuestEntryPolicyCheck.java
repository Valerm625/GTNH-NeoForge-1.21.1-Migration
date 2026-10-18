package org.gtnhmigration.compat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

public final class LegacyQuestEntryPolicyCheck {
    static int assertions,comparisons;static final UUID ACTOR=new UUID(0,90001),OTHER=new UUID(0,90002);
    static LegacyQuestDefinitionArchive archive;static HolderLookup.Provider registries;
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static void reject(Runnable action){try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|NullPointerException expected){assertions++;}}
    static UUID id(long value){return new UUID(0,value);}
    static State state(Collection<UUID> actor,Collection<UUID> other){Map<UUID,Map<UUID,CompoundTag>> all=new LinkedHashMap<>();for(UUID q:actor)all.computeIfAbsent(q,k->new LinkedHashMap<>()).put(ACTOR,new CompoundTag());for(UUID q:other)all.computeIfAbsent(q,k->new LinkedHashMap<>()).put(OTHER,new CompoundTag());return new State(all,Map.of());}
    static List<CompoundTag> raw(LegacyQuestDefinitionArchive a){return a.questIds().stream().sorted().map(q->a.quest(q).nbt().orElseThrow()).toList();}
    static void compare(LegacyQuestEntryPolicy policy,State state,List<CompoundTag> raw) {
        BqQuestEntryReference.install(raw,state.questCompletions());var view=policy.view(state,ACTOR);
        for(UUID id:policy.nodes().keySet()) {
            var expected=BqQuestEntryReference.quest(id);check(view.isComplete(id)==expected.isComplete(ACTOR),"Source completion mismatch: "+id);comparisons++;
            check(view.isUnlocked(id)==expected.isUnlocked(ACTOR),"Source unlock mismatch: "+id);comparisons++;
            if(policy.nodes().get(id).repeatTime()<0){var actual=view.nonRepeatingStatus(id);check(actual.canSubmit()==expected.canSubmit(new BqQuestEntryReference.EntityPlayer(ACTOR)),"Source canSubmit mismatch: "+id);comparisons++;}
        }
    }
    static void blob(DataOutputStream out,byte[] bytes)throws IOException{out.writeInt(bytes.length);out.write(bytes);}
    static void text(DataOutputStream out,String text)throws IOException{blob(out,text.getBytes(StandardCharsets.UTF_8));}
    static JsonObject quest(long low,String logic,boolean global,long... parents) {
        JsonObject q=JsonParser.parseString(new String(archive.document(archive.questPath(id(0))),StandardCharsets.UTF_8)).getAsJsonObject();q.addProperty("questIDLow:4",low);
        var props=q.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10");props.addProperty("questLogic:8",logic);props.addProperty("isGlobal:1",global?1:0);props.addProperty("repeatTime:3",-1);
        JsonObject refs=new JsonObject();int index=0;for(long parent:parents){JsonObject ref=new JsonObject();ref.addProperty("questIDHigh:4",0);ref.addProperty("questIDLow:4",parent);refs.add((index++)+":10",ref);}q.add("preRequisites:9",refs);return q;
    }
    static LegacyQuestDefinitionArchive fixture(List<JsonObject> quests)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBytes("GTNHQDA1");out.writeInt(quests.size()+1);
        byte[] settings="{\"format:8\":\"3.1.0\",\"betterquesting:10\":{\"editMode:1\":0}}".getBytes(StandardCharsets.UTF_8);text(out,"QuestSettings.json");out.writeByte(0);blob(out,settings);out.write(HexFormat.of().parseHex(digest(settings)));
        for(JsonObject q:quests){long low=q.get("questIDLow:4").getAsLong();text(out,"Quests/"+low+".json");out.writeByte(1);out.writeLong(0);out.writeLong(low);byte[] raw=q.toString().getBytes(StandardCharsets.UTF_8);blob(out,raw);out.write(HexFormat.of().parseHex(digest(raw)));}
        int split=bytes.size();blob(out,"[]".getBytes(StandardCharsets.UTF_8));out.writeInt(1);text(out,"minecraft:dirt");out.writeInt(0);text(out,"minecraft:dirt");
        byte[] raw=bytes.toByteArray();return LegacyQuestDefinitionArchive.decode(raw,new LegacyQuestDefinitionArchive.Pin(digest(raw),new Binding(digest(Arrays.copyOfRange(raw,0,split)),digest(Arrays.copyOfRange(raw,split,raw.length)))));
    }
    static void actual()throws Exception {
        var policy=LegacyQuestEntryPolicy.fromArchive(archive);check(policy.nodes().size()==3814,"Source quest graph incomplete");
        check(policy.nodes().values().stream().mapToInt(n->n.sourceReferences().size()).sum()==5460,"Source edges lost");
        check(policy.missingReferences().size()==3,"Missing source references changed");
        Set<String> missing=new HashSet<>();for(var m:policy.missingReferences())missing.add(m.quest().getLeastSignificantBits()+":"+m.parent().getLeastSignificantBits());check(missing.equals(Set.of("1839:1930","2057:1807","2058:1951")),"Source missing reference identities changed");
        var raw=raw(archive);List<UUID> all=new ArrayList<>(new TreeSet<>(archive.questIds()));
        compare(policy,State.empty(),raw);compare(policy,state(all,List.of()),raw);compare(policy,state(List.of(),all),raw);
        for(int seed=0;seed<8;seed++){Random random=new Random(2842101L+seed);List<UUID> own=new ArrayList<>(),other=new ArrayList<>();for(UUID q:all){if(random.nextBoolean())own.add(q);if(random.nextBoolean())other.add(q);}compare(policy,state(own,other),raw);}
        var key=new TaskKey(id(0),0,"bq_standard:retrieval");var onlyTask=new State(Map.of(),Map.of(key,new LegacyRetrievalEngine.State(Map.of(),Set.of(ACTOR))));check(!policy.view(onlyTask,ACTOR).isUnlocked(id(2)),"Task completion silently promoted quest completion");
        check(policy.view(state(List.of(id(0)),List.of()),ACTOR).isUnlocked(id(2)),"Real parent quest completion did not unlock child");
        var bound=LegacyRetrievalDefinitionBinding.inspect(archive,id(27),registries).requireBound();var locked=policy.view(State.empty(),ACTOR).manualStatus(bound);check(!locked.unlocked(),"Real charcoal quest should start locked");
        Inventory inv=new Inventory(null);inv.items.set(0,new ItemStack(Items.CHARCOAL,8));var before=LegacyRetrievalInventoryBridge.prepare(bound.quest(),Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inv),registries,locked.gate(true));check(!before.plan().evaluated() && inv.items.get(0).getCount()==8,"Locked source quest consumed inventory");
        var progress=state(policy.nodes().get(id(27)).prerequisites(),List.of());var unlocked=policy.view(progress,ACTOR).manualStatus(bound);check(unlocked.unlocked() && unlocked.canSubmit() && !unlocked.complete(),"Completed parents did not allow eligible source detection");
        var noCache=LegacyRetrievalInventoryBridge.prepare(bound.quest(),Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inv),registries,unlocked.gate(false));check(!noCache.plan().evaluated(),"Absent quest cache was inferred from ledger readiness");
        var ready=LegacyRetrievalInventoryBridge.prepare(bound.quest(),Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inv),registries,unlocked.gate(true));check(ready.plan().allTasksCompleteForActor(),"Source gate did not compose with item binding");ready.applyCountsWithoutPublishing(()->true);check(inv.items.get(0).getCount()==4,"Gate-to-inventory count application differs");
        List<UUID> completed=new ArrayList<>(policy.nodes().get(id(27)).prerequisites());completed.add(id(27));var done=policy.view(state(completed,List.of()),ACTOR).manualStatus(bound);check(done.complete() && !done.canSubmit(),"Completed nonrepeat quest can submit");
        var stopped=LegacyRetrievalInventoryBridge.prepare(bound.quest(),Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inv),registries,done.gate(true));check(!stopped.plan().evaluated() && inv.items.get(0).getCount()==4,"Completed source quest replay consumed");
    }
    static void synthetic()throws Exception {
        for(var logic:LegacyQuestEntryPolicy.Logic.values())for(int total=0;total<=5;total++)for(int mask=0;mask<(1<<total);mask++){
            List<JsonObject> quests=new ArrayList<>();long[] parents=new long[total];List<UUID> complete=new ArrayList<>();for(int i=0;i<total;i++){parents[i]=100+i;quests.add(quest(100+i,"AND",false));if((mask&(1<<i))!=0)complete.add(id(100+i));}quests.add(quest(200,logic.name(),false,parents));
            var a=fixture(quests);compare(LegacyQuestEntryPolicy.fromArchive(a),state(complete,List.of()),raw(a));
        }
        var target=quest(1,"AND",false,2,2,3);target.getAsJsonObject("preRequisites:9").getAsJsonObject("0:10").addProperty("type:1",2);target.getAsJsonObject("preRequisites:9").getAsJsonObject("2:10").addProperty("type:1",1);
        var a=fixture(List.of(target,quest(2,"AND",false),quest(3,"AND",true)));var policy=LegacyQuestEntryPolicy.fromArchive(a);check(policy.nodes().get(id(1)).sourceReferences().size()==3 && policy.nodes().get(id(1)).prerequisites().size()==2,"Source Set semantics or original duplicate edge lost");check(policy.nodes().get(id(1)).visibilityTypes().equals(Map.of(id(2),(byte)2,id(3),(byte)1)),"Visibility or absent duplicate override lost");
        var both=state(List.of(id(2)),List.of(id(3)));compare(policy,both,raw(a));check(policy.view(both,ACTOR).isUnlocked(id(1)),"Global prerequisite completion ignored");check(!policy.view(state(List.of(),List.of(id(2),id(3))),ACTOR).isUnlocked(id(1)),"Other player's normal completion leaked");check(!policy.view(state(List.of(id(2)),List.of()),ACTOR).isUnlocked(id(1)),"Hidden/implicit parent dropped");
        var global=policy.view(state(List.of(),List.of(id(3))),ACTOR).nonRepeatingStatus(id(3));check(global.complete() && global.canSubmit() && global.gate(true).questAlreadyComplete(),"Global early completion and actor canSubmit branches collapsed");
        var cycle=fixture(List.of(quest(1,"AND",false,2),quest(2,"AND",false,1)));var cyclic=LegacyQuestEntryPolicy.fromArchive(cycle);compare(cyclic,State.empty(),raw(cycle));compare(cyclic,state(List.of(id(1)),List.of()),raw(cycle));check(cyclic.view(state(List.of(id(1)),List.of()),ACTOR).isUnlocked(id(2)),"Direct source prerequisites incorrectly recurse");
        var missing=fixture(List.of(quest(1,"AND",false,2,999),quest(2,"AND",false)));var incomplete=LegacyQuestEntryPolicy.fromArchive(missing);var impossible=state(List.of(id(2),id(999)),List.of());compare(incomplete,impossible,raw(missing));var blocked=incomplete.view(impossible,ACTOR).nonRepeatingStatus(id(1));check(!blocked.unlocked() && blocked.requiredPrerequisites()==2 && blocked.completedPrerequisites()==1,"Missing source parent removed from denominator");
        var repeat=quest(1,"AND",false);repeat.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10").addProperty("repeatTime:3",0);var repeating=LegacyQuestEntryPolicy.fromArchive(fixture(List.of(repeat)));reject(()->repeating.view(State.empty(),ACTOR).nonRepeatingStatus(id(1)));
        reject(()->policy.view(State.empty(),ACTOR).isUnlocked(id(999)));reject(()->policy.view(State.empty(),null));
        var bound=LegacyRetrievalDefinitionBinding.inspect(archive,id(0),registries).requireBound();reject(()->policy.view(State.empty(),ACTOR).manualStatus(bound));
        var mutable=new CompoundTag();mutable.putBoolean("claimed",true);mutable.putLong("timestamp",0);State saved=new State(Map.of(id(2),Map.of(ACTOR,mutable)),Map.of());var view=policy.view(saved,ACTOR);mutable.remove("claimed");check(view.isComplete(id(2)) && !view.nonRepeatingStatus(id(2)).canSubmit(),"Claim/timestamp or caller mutation changed presence semantics");
        var invalid=quest(1,"UNKNOWN",false);var invalidArchive=fixture(List.of(invalid));reject(()->LegacyQuestEntryPolicy.fromArchive(invalidArchive));
    }
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);archive=LegacyQuestDefinitionArchive.loadPinned(Path.of(args[0]));actual();synthetic();
        System.out.println("{\"test\":\"LegacyQuestEntryPolicyCheck\",\"passed\":true,\"assertions\":"+assertions+",\"source_comparisons\":"+comparisons+",\"source_quests\":3814,\"source_prerequisite_edges\":5460,\"missing_source_references\":3,\"logic_modes_verified\":6,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false,\"full_parity\":false}");
    }
}

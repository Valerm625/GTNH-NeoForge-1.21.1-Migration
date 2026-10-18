package org.gtnhmigration.compat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyRetrievalDefinitionBinding.BoundQuest;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

public final class LegacyQuestCompletionPlannerCheck {
    static int assertions,comparisons,passes;static final UUID ACTOR=new UUID(0,90001),OTHER=new UUID(0,90002);
    static LegacyQuestDefinitionArchive archive;static HolderLookup.Provider registries;
    static final Map<LegacyQuestDefinitionArchive,List<CompoundTag>> RAW=new IdentityHashMap<>();
    static final Map<LegacyQuestDefinitionArchive,LegacyQuestCompletionPlanner> PLANNERS=new IdentityHashMap<>();
    static final LegacyQuestCompletionPlanner.Tick RUN=new LegacyQuestCompletionPlanner.Tick(false,true,false,60);
    static void check(boolean condition,String why){assertions++;if(!condition)throw new AssertionError(why);}
    static void equal(Object actual,Object expected,String why){assertions++;if(!Objects.equals(actual,expected))throw new AssertionError(why+": "+actual+" != "+expected);}
    static void reject(Runnable action){try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|NullPointerException|UnsupportedOperationException expected){assertions++;}}
    static UUID id(long value){return new UUID(0,value);}
    static BoundQuest bound(LegacyQuestDefinitionArchive a,long id){return LegacyRetrievalDefinitionBinding.inspect(a,id(id),registries).requireBound();}
    static List<CompoundTag> raw(LegacyQuestDefinitionArchive a){return RAW.computeIfAbsent(a,key->key.questIds().stream().sorted().map(q->key.quest(q).nbt().orElseThrow()).toList());}
    static Map<UUID,Map<UUID,CompoundTag>> completions(Collection<UUID> quests,UUID actor){Map<UUID,Map<UUID,CompoundTag>> out=new LinkedHashMap<>();for(UUID q:quests){CompoundTag record=new CompoundTag();record.putString("uuid",actor.toString());out.put(q,Map.of(actor,record));}return out;}
    static Map<TaskKey,LegacyRetrievalEngine.State> latches(List<BoundQuest> defs,UUID actor,boolean complete){Map<TaskKey,LegacyRetrievalEngine.State> out=new LinkedHashMap<>();for(var def:defs)for(var task:def.quest().tasks()){var key=new TaskKey(def.quest().id(),task.index(),task.type());out.put(key,new LegacyRetrievalEngine.State(Map.of(actor,task.retrieval().requirements().stream().map(r->r.count()).toList()),complete?Set.of(actor):Set.of()));}return out;}
    static LegacyQuestCompletionPlanner.Plan compare(LegacyQuestDefinitionArchive a,State before,List<BoundQuest> active,LegacyQuestCompletionPlanner.Tick tick) {
        var planner=PLANNERS.computeIfAbsent(a,LegacyQuestCompletionPlanner::new);var nativeClock=new AtomicLong(1700000000000L);var sourceClock=new AtomicLong(1700000000000L);
        var actual=planner.plan(before,ACTOR,active,tick,()->nativeClock.getAndAdd(17));
        var expected=BqQuestCompletionReference.run(raw(a),before,ACTOR,active.stream().map(d->d.quest().id()).toList(),tick,()->sourceClock.getAndAdd(17));
        equal(actual.after(),expected.after(),"Source completion records");equal(actual.updatedQuests(),expected.updated(),"Source update calls");
        equal(actual.completedEventQuests(),expected.completed(),"Source event list");equal(actual.completionNotices(),expected.notices(),"Source notice intents");
        equal(actual.dirtyPlayers(),expected.dirtyPlayers(),"Source player dirtiness");equal(actual.dirtyQuests(),expected.dirtyQuests(),"Source cache dirtiness");
        equal(actual.completionEventRequired(),expected.posted(),"Source empty event behavior");equal(actual.refreshCache(),!expected.completed().isEmpty(),"Source completion refresh signal");
        equal(nativeClock.get(),sourceClock.get(),"Source clock calls");equal(actual.after().tasks(),before.tasks(),"Completion changed retrieval state");
        equal(new HashSet<>(planner.activeProjection(before,ACTOR,active)),BqQuestCompletionReference.active(raw(a),before,ACTOR,active.stream().map(d->d.quest().id()).toList()),"Source active membership");
        comparisons+=10;passes++;return actual;
    }
    static void actual()throws Exception {
        List<BoundQuest> supported=new ArrayList<>();for(UUID q:new TreeSet<>(archive.questIds()))LegacyRetrievalDefinitionBinding.inspect(archive,q,registries).bound().ifPresent(supported::add);
        equal(supported.size(),39,"Bound source domain changed");var policy=LegacyQuestEntryPolicy.fromArchive(archive);
        for(var def:supported) {
            List<UUID> otherQuests=new ArrayList<>(archive.questIds());otherQuests.remove(def.quest().id());var parents=completions(otherQuests,ACTOR);
            compare(archive,new State(parents,Map.of()),List.of(def),RUN);
            var countsOnly=new State(parents,latches(List.of(def),ACTOR,false));var noLatch=compare(archive,countsOnly,List.of(def),RUN);check(!policy.view(noLatch.after(),ACTOR).isComplete(def.quest().id()),"Counters were promoted without task latch");
            var ready=new State(parents,latches(List.of(def),ACTOR,true));var completed=compare(archive,ready,List.of(def),RUN);check(policy.view(completed.after(),ACTOR).isComplete(def.quest().id()),"Source-bound task did not complete");
            check(!completed.after().questCompletions().get(def.quest().id()).get(ACTOR).getBoolean("claimed"),"Completion auto-claimed reward");
            compare(archive,completed.after(),List.of(def),RUN);
            compare(archive,new State(parents,latches(List.of(def),OTHER,true)),List.of(def),RUN);
        }
        for(int tick:new int[]{0,1,19,20,40,59,60,61,120,200,-60})for(int mask=0;mask<8;mask++) {
            var context=new LegacyQuestCompletionPlanner.Tick((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,tick);
            compare(archive,new State(Map.of(),latches(List.of(bound(archive,0)),ACTOR,true)),List.of(bound(archive,0)),context);
        }
        var empty=compare(archive,State.empty(),List.of(),RUN);check(empty.completionEventRequired() && !empty.refreshCache(),"Empty pass event missing");
        var zero=bound(archive,0);var before=new State(Map.of(),latches(List.of(zero),ACTOR,true));
        check(!policy.view(before,ACTOR).isUnlocked(id(2)),"Real child unlocked from latch alone");
        var after=compare(archive,before,List.of(zero),RUN);check(policy.view(after.after(),ACTOR).isUnlocked(id(2)),"Real child not unlocked by source completion");
        check(!policy.view(after.after(),OTHER).isUnlocked(id(2)),"Completion leaked across UUIDs");
    }
    static void blob(DataOutputStream out,byte[] bytes)throws IOException{out.writeInt(bytes.length);out.write(bytes);}
    static void text(DataOutputStream out,String text)throws IOException{blob(out,text.getBytes(StandardCharsets.UTF_8));}
    static JsonObject quest(long low,long... parents) {
        JsonObject q=JsonParser.parseString(new String(archive.document(archive.questPath(id(0))),StandardCharsets.UTF_8)).getAsJsonObject();q.addProperty("questIDLow:4",low);
        var props=q.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10");props.addProperty("questLogic:8","AND");props.addProperty("isGlobal:1",0);props.addProperty("repeatTime:3",-1);props.addProperty("lockedProgress:1",0);props.addProperty("isSilent:1",0);
        JsonObject refs=new JsonObject();int i=0;for(long parent:parents){JsonObject ref=new JsonObject();ref.addProperty("questIDHigh:4",0);ref.addProperty("questIDLow:4",parent);refs.add((i++)+":10",ref);}q.add("preRequisites:9",refs);return q;
    }
    static LegacyQuestDefinitionArchive fixture(List<JsonObject> quests)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBytes("GTNHQDA1");out.writeInt(quests.size()+1);
        byte[] settings="{\"format:8\":\"3.1.0\",\"betterquesting:10\":{\"editMode:1\":0}}".getBytes(StandardCharsets.UTF_8);text(out,"QuestSettings.json");out.writeByte(0);blob(out,settings);out.write(HexFormat.of().parseHex(digest(settings)));
        for(JsonObject q:quests){long low=q.get("questIDLow:4").getAsLong();text(out,"Quests/"+low+".json");out.writeByte(1);out.writeLong(0);out.writeLong(low);byte[] raw=q.toString().getBytes(StandardCharsets.UTF_8);blob(out,raw);out.write(HexFormat.of().parseHex(digest(raw)));}
        int split=bytes.size();blob(out,"[]".getBytes(StandardCharsets.UTF_8));out.writeInt(1);text(out,"minecraft:dirt");out.writeInt(0);text(out,"minecraft:dirt");byte[] raw=bytes.toByteArray();
        return LegacyQuestDefinitionArchive.decode(raw,new LegacyQuestDefinitionArchive.Pin(digest(raw),new Binding(digest(Arrays.copyOfRange(raw,0,split)),digest(Arrays.copyOfRange(raw,split,raw.length)))));
    }
    static void synthetic()throws Exception {
        var child=quest(101,100);child.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10").addProperty("lockedProgress:1",1);
        var a=fixture(List.of(quest(100),child));var parent=bound(a,100);var childBound=bound(a,101);var ordered=List.of(parent,childBound);var reverse=List.of(childBound,parent);
        var before=new State(Map.of(),latches(ordered,ACTOR,true));var forward=compare(a,before,ordered,RUN);var backward=compare(a,before,reverse,RUN);
        equal(forward.completedEventQuests(),List.of(id(100),id(101)),"Source pass order did not allow newly unlocked active child");equal(backward.completedEventQuests(),List.of(id(100)),"Source pass incorrectly revisited child");
        equal(compare(a,backward.after(),List.of(childBound),RUN).completedEventQuests(),List.of(id(101)),"Next pass lost child completion");
        equal(compare(a,before,List.of(childBound),RUN).completedEventQuests(),List.of(),"Locked progress completed locked quest");
        equal(new LegacyQuestCompletionPlanner(a).activeProjection(before,ACTOR,ordered),List.of(id(100),id(101)),"Locked progress missing from active subset");
        child.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10").addProperty("isSilent:1",1);var quiet=fixture(List.of(quest(100),child));var quietDefs=List.of(bound(quiet,100),bound(quiet,101));
        var silent=compare(quiet,new State(Map.of(),latches(quietDefs,ACTOR,true)),quietDefs,RUN);equal(silent.completionNotices(),List.of(id(100)),"Silent source quest produced notice");equal(silent.completedEventQuests(),List.of(id(100),id(101)),"Silent suppressed completion event");
        CompoundTag claimed=new CompoundTag();claimed.putBoolean("claimed",true);claimed.putLong("timestamp",Long.MIN_VALUE);claimed.putString("uuid",ACTOR.toString().toUpperCase(Locale.ROOT));claimed.putIntArray("foreign",new int[]{7,8,9});
        CompoundTag otherClaimed=claimed.copy();otherClaimed.putString("uuid",OTHER.toString());
        var original=new State(Map.of(id(100),Map.of(ACTOR,claimed,OTHER,otherClaimed)),latches(ordered,ACTOR,true));var repeated=compare(a,original,List.of(parent),RUN);equal(repeated.after(),original,"Stale cache rewrote existing completion compound");check(repeated.dirtyPlayers().isEmpty() && repeated.dirtyQuests().contains(id(100)),"Stale cache confused player and quest dirtiness");
        claimed.putString("foreignLater","caller mutation");equal(repeated.after(),original,"NBT input copy lost");
        var planner=new LegacyQuestCompletionPlanner(a);var clock=new AtomicLong();reject(()->planner.plan(before,ACTOR,List.of(parent,parent),RUN,clock::incrementAndGet));reject(()->planner.plan(before,ACTOR,List.of(parent,bound(archive,0)),RUN,clock::incrementAndGet));
        Map<TaskKey,LegacyRetrievalEngine.State> bad=new LinkedHashMap<>(before.tasks());bad.put(new TaskKey(id(101),99,"unknown:task"),LegacyRetrievalEngine.State.empty());reject(()->planner.plan(new State(Map.of(),bad),ACTOR,ordered,RUN,clock::incrementAndGet));equal(clock.get(),0L,"Partial pass before preflight rejection");
        try{planner.plan(before,ACTOR,ordered,RUN,()->{if(clock.incrementAndGet()==2)throw new IllegalStateException("clock fixture");return 1L;});throw new AssertionError("Expected clock failure");}catch(IllegalStateException expected){assertions++;}
        check(before.questCompletions().isEmpty(),"Failed plan leaked earlier completion");
        reject(()->planner.plan(before,null,ordered,RUN,()->0));reject(()->forward.completedEventQuests().add(id(9)));
        var multi=quest(200);JsonObject originalTask=multi.getAsJsonObject("tasks:9").getAsJsonObject("0:10");JsonObject taskRows=new JsonObject();int[] indices={17,3,41};
        for(int i=0;i<indices.length;i++){JsonObject task=originalTask.deepCopy();task.addProperty("index:3",indices[i]);taskRows.add(i+":10",task);}multi.add("tasks:9",taskRows);
        var multiArchive=fixture(List.of(multi));var multiDef=bound(multiArchive,200);
        for(int mask=0;mask<8;mask++){Map<TaskKey,LegacyRetrievalEngine.State> states=new LinkedHashMap<>();for(int i=0;i<indices.length;i++)if((mask&(1<<i))!=0)states.put(new TaskKey(id(200),indices[i],"bq_standard:retrieval"),new LegacyRetrievalEngine.State(Map.of(),Set.of(ACTOR)));
            var result=compare(multiArchive,new State(Map.of(),states),List.of(multiDef),RUN);equal(result.completedEventQuests(),mask==7?List.of(id(200)):List.of(),"Sparse task indexes or missing task latch changed AND completion");}
        var invalid=quest(300);var invalidProps=invalid.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10");invalidProps.remove("isSilent:1");invalidProps.addProperty("isSilent:8","false");var badArchive=fixture(List.of(invalid));var badBound=bound(badArchive,300);
        reject(()->new LegacyQuestCompletionPlanner(badArchive).plan(State.empty(),ACTOR,List.of(badBound),RUN,()->0));

    }
    static void inventoryAndLedger()throws Exception {
        var def=bound(archive,27);var policy=LegacyQuestEntryPolicy.fromArchive(archive);var before=new State(completions(policy.nodes().get(id(27)).prerequisites(),ACTOR),Map.of());
        Inventory inv=new Inventory(null);inv.items.set(0,new ItemStack(Items.CHARCOAL,8));var planner=new LegacyQuestCompletionPlanner(archive);
        Path dir=Files.createTempDirectory("gtnh-completion-ledger-");var catalog=archive.progressCatalog();
        var source=new SourceDocument("fixture/original-progress",new byte[]{0,1,2,3,4});
        try(var ledger=LegacyQuestProgressFileStore.open(dir,archive.binding(),catalog)) {
            var head=ledger.commit(Optional.empty(),before,List.of(source));var detect=LegacyRetrievalInventoryBridge.prepare(def.quest(),Map.of(),ACTOR,List.of(ACTOR,OTHER),Map.of(ACTOR,inv),registries,policy.view(before,ACTOR).manualStatus(def).gate(true));
            CompoundTag player=new CompoundTag();player.putUUID("UUID",ACTOR);ListTag items=new ListTag();CompoundTag slot=new CompoundTag();slot.putByte("Slot",(byte)0);items.add(inv.items.get(0).save(registries,slot));player.put("Inventory",items);var proposal=LegacyRetrievalTransactionProposal.bind(detect,Optional.of(head),catalog,player);
            check(!policy.view(proposal.afterState(),ACTOR).isComplete(id(27)),"Detection prematurely completed quest");
            var taskHead=ledger.commit(Optional.of(head.sha256()),proposal.afterState(),List.of(source));detect.applyCountsWithoutPublishing(()->true);equal(inv.items.get(0).getCount(),4,"Charcoal consumption differs");
            equal(planner.plan(proposal.afterState(),ACTOR,List.of(def),new LegacyQuestCompletionPlanner.Tick(false,true,false,20),()->10).after(),proposal.afterState(),"Quest completed before source tick");
            var done=compare(archive,proposal.afterState(),List.of(def),RUN);ledger.commit(Optional.of(taskHead.sha256()),done.after(),List.of(source));
            check(done.after().tasks().values().stream().anyMatch(t->t.completed().contains(OTHER)),"Offline task latch lost");check(!policy.view(done.after(),OTHER).isComplete(id(27)),"Offline task member got quest completion automatically");
        }
        try(var reopened=LegacyQuestProgressFileStore.open(dir,archive.binding(),catalog)) {
            var head=reopened.current().orElseThrow();var state=head.frame().snapshot().state();check(policy.view(state,ACTOR).isComplete(id(27)),"Completion lost after ledger reopen");equal(head.frame().snapshot().sources(),List.of(source),"Original source bytes lost");
            var tasks=new LinkedHashMap<Integer,LegacyRetrievalEngine.State>();state.tasks().forEach((key,value)->{if(key.questId().equals(id(27)))tasks.put(key.index(),value);});
            var replay=LegacyRetrievalInventoryBridge.prepare(def.quest(),tasks,ACTOR,List.of(ACTOR),Map.of(ACTOR,inv),registries,policy.view(state,ACTOR).manualStatus(def).gate(true));check(!replay.plan().evaluated() && inv.items.get(0).getCount()==4,"Completed quest consumed again after reload");
            equal(planner.activeProjection(state,ACTOR,List.of(def)),List.of(),"Completed quest remained active after cache refresh");
        }
    }
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);archive=LegacyQuestDefinitionArchive.loadPinned(Path.of(args[0]));actual();synthetic();inventoryAndLedger();
        System.out.println("{\"test\":\"LegacyQuestCompletionPlannerCheck\",\"passed\":true,\"assertions\":"+assertions+",\"source_comparisons\":"+comparisons+",\"source_passes\":"+passes+",\"bound_source_quests\":39,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false,\"full_parity\":false}");
    }
}

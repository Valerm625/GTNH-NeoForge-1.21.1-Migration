import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;

/** Actual definition + synthetic progress + fixture items + independent file ledger.
 * This is not a world save importer, logWood registry mapping or inventory write.
 */
public final class LegacyProgressImportPipelineCheck {
    static int assertions;
    static final UUID PLAYER=new UUID(1,1);
    static void check(boolean condition,String message) {
        assertions++;
        if(!condition) throw new AssertionError(message);
    }
    static LegacyTypedNbtJsonReader.Result decode(String json) {
        return LegacyTypedNbtJsonReader.inspect(json.getBytes(StandardCharsets.UTF_8),"synthetic-progress");
    }
    public static void main(String[] args) throws Exception {
        byte[] definitionBytes=Files.readAllBytes(Path.of(args[0]));
        var decoded=LegacyTypedNbtJsonReader.inspect(definitionBytes,"pinned-mainline-quest-definition");
        check(decoded.accepted(),"Actual definition did not decode");
        CompoundTag definition=decoded.nbt().orElseThrow();
        UUID questId=new UUID(definition.getLong("questIDHigh"),definition.getLong("questIDLow"));
        check(questId.equals(new UUID(0,2)),"Wrong source quest identity");
        List<Definition> definitions=new ArrayList<>();
        List<LegacyQuestRetrievalPlanner.Task<String>> tasks=new ArrayList<>();
        ListTag sourceTasks=definition.getList("tasks",Tag.TAG_COMPOUND);
        check(sourceTasks.size()==2,"Unexpected definition task count");
        for(Tag tag:sourceTasks) {
            CompoundTag task=(CompoundTag)tag;
            int index=task.getInt("index");
            String factory=task.getString("taskID");
            ListTag required=task.getList("requiredItems",Tag.TAG_COMPOUND);
            definitions.add(new Definition(new TaskKey(questId,index,factory),required.size()));
            List<Requirement<String>> requirements=new ArrayList<>();
            for(Tag itemTag:required) {
                CompoundTag item=(CompoundTag)itemTag;
                // Names are synthetic source-symbol keys, never modern tags/IDs.
                String token=item.getString("OreDict").isEmpty()?"source-id:"+item.getString("id"):"source-ore:"+item.getString("OreDict");
                requirements.add(new Requirement<>(item.getInt("Count"), token::equals));
            }
            Config<String> config=new Config<>(requirements,task.getBoolean("consume"),task.getBoolean("autoConsume"),task.getBoolean("groupDetect"),task.getBoolean("requireOnlyOneItem"));
            tasks.add(LegacyQuestRetrievalPlanner.Task.retrieval(index,config));
        }
        Catalog catalog=new Catalog(List.of(questId),definitions);
        String player=PLAYER.toString();
        String raw="{\"questProgress:9\":{\"8:10\":{\"questIDHigh:4\":0,\"questIDLow:4\":2,\"completed:9\":{},\"tasks:9\":{"
            +"\"99:10\":{\"index:3\":1,\"taskID:8\":\"bq_standard:retrieval\",\"userProgress:9\":{\"5:10\":{\"uuid:8\":\""+player+"\",\"data:11\":[0]}}},"
            +"\"2:10\":{\"index:3\":0,\"taskID:8\":\"bq_standard:retrieval\",\"userProgress:9\":{\"1:10\":{\"uuid:8\":\""+player+"\",\"data:11\":[1,2]}}}"
            +"}}}}";
        var progress=decode(raw);
        check(progress.accepted(),"Synthetic progress decoding failed");
        CompoundTag original=progress.nbt().orElseThrow();
        check(original.getList("questProgress",10).getCompound(0).getList("tasks",10).getCompound(0).getInt("index")==1,
            "JSON list order was sorted instead of preserved");
        var imported=LegacyQuestProgressEnvelope.readLegacy(original,catalog,LegacyQuestProgressEnvelope.State.empty(),false);
        check(imported.accepted(),"Progress envelope rejected known identity");
        var state=imported.candidate().orElseThrow();
        var binding=new LegacyQuestProgressStoreCodec.Binding(
            LegacyQuestProgressStoreCodec.digest(definitionBytes),
            LegacyQuestProgressStoreCodec.digest("unmapped-source-symbol-fixture".getBytes(StandardCharsets.UTF_8)));
        var archive=List.of(new LegacyQuestProgressStoreCodec.SourceDocument("actual-definition",definitionBytes),
            new LegacyQuestProgressStoreCodec.SourceDocument("synthetic-progress",progress.rawUtf8()));
        Path storeDirectory=Path.of(args[1]);
        String importedHead;
        try(var store=LegacyQuestProgressFileStore.open(storeDirectory,binding,catalog)) {
            importedHead=store.commit(Optional.empty(),state,archive).sha256();
        }
        try(var store=LegacyQuestProgressFileStore.open(storeDirectory,binding,catalog)) {
            var reloaded=store.current().orElseThrow();
            check(reloaded.frame().snapshot().state().equals(state),"Imported progress lost after file reload");
            check(reloaded.frame().snapshot().sources().equals(archive),"Source archives lost after file reload");
            state=reloaded.frame().snapshot().state();
        }
        var key0=new TaskKey(questId,0,"bq_standard:retrieval");
        var key1=new TaskKey(questId,1,"bq_standard:retrieval");
        check(state.tasks().get(key0).progress().get(PLAYER).equals(List.of(1,2)),"Progress routed by JSON position instead of task index");
        check(state.tasks().get(key1).progress().get(PLAYER).equals(List.of(0)),"Consuming progress routed incorrectly");
        Map<Integer,LegacyRetrievalEngine.State> before=Map.of(0,state.tasks().get(key0),1,state.tasks().get(key1));
        var inventory=List.of(new Slot<>(0,"source-id:minecraft:gravel",9),new Slot<>(1,"source-ore:logWood",5));
        var context=new Context<>(PLAYER,List.of(PLAYER),Map.of(PLAYER,inventory));
        // Quest policy and authorization remain explicit test fixtures.
        var quest=new LegacyQuestRetrievalPlanner.Quest<>(questId,tasks,"AND",-1,false,false,false,false);
        var gate=new LegacyQuestRetrievalPlanner.Gate(true,true,true,false);
        var plan=LegacyQuestRetrievalPlanner.plan(quest,before,context,gate);
        check(plan.allTasksCompleteForActor(),"Source 0:2 fixture did not complete both tasks");
        check(plan.afterInventories().get(PLAYER).get(1).count()==3,"Expected five logs minus two exactly once");
        check(plan.detections().stream().map(LegacyQuestRetrievalPlanner.Detection::taskIndex).toList().equals(List.of(0,1)),"Planner did not use original task index order");
        check(state.questCompletions().get(questId).isEmpty(),"Task calculation invented quest completion/reward state");
        check(state.tasks().get(key0).progress().get(PLAYER).equals(List.of(1,2)),"Imported snapshot changed during planning");
        check(inventory.get(1).count()==5,"Input inventory mutated");
        check(Arrays.equals(progress.rawUtf8(),raw.getBytes(StandardCharsets.UTF_8)),"Original progress bytes were lost");
        check(Arrays.equals(decoded.rawUtf8(),definitionBytes),"Original definition bytes were lost");
        var replay=LegacyQuestRetrievalPlanner.plan(quest,plan.afterStates(),new Context<>(PLAYER,List.of(PLAYER),plan.afterInventories()),gate);
        check(replay.consumed().isEmpty() && replay.afterInventories().get(PLAYER).get(1).count()==3,"Repeated submission consumed again");
        var plannedState=new LegacyQuestProgressEnvelope.State(state.questCompletions(),
            Map.of(key0,plan.afterStates().get(0),key1,plan.afterStates().get(1)));
        try(var store=LegacyQuestProgressFileStore.open(storeDirectory,binding,catalog)) {
            store.commit(Optional.of(importedHead),plannedState,archive);
        }
        try(var store=LegacyQuestProgressFileStore.open(storeDirectory,binding,catalog)) {
            var reloaded=store.current().orElseThrow().frame().snapshot().state();
            check(reloaded.equals(plannedState),"Planned UUID state did not survive reload");
            var after=Map.of(0,reloaded.tasks().get(key0),1,reloaded.tasks().get(key1));
            var replayAfterRestart=LegacyQuestRetrievalPlanner.plan(quest,after,
                new Context<>(PLAYER,List.of(PLAYER),plan.afterInventories()),gate);
            check(replayAfterRestart.consumed().isEmpty(),"Reloaded task latch lost; repeated plan would consume");
            check(reloaded.questCompletions().get(questId).isEmpty(),"Store invented quest completion or reward");
        }
        String bad=raw.replace("\"index:3\":1","\"index:3\":7");
        var decodedBad=decode(bad);
        check(decodedBad.accepted(),"Typed reader should not pretend to validate task identity");
        var rejected=LegacyQuestProgressEnvelope.readLegacy(decodedBad.nbt().orElseThrow(),catalog,state,false);
        check(!rejected.accepted() && rejected.candidate().isEmpty(),"Missing task identity partially applied");
        check(rejected.original().equals(decodedBad.nbt().orElseThrow()),"Rejected envelope lost raw NBT");
        check(state.tasks().get(key0).progress().get(PLAYER).equals(List.of(1,2)),"Rejected apply changed prior state");
        var malformed=decode(raw.replace("\"index:3\":1","\"index:3\":1,\"index:3\":0"));
        check(!malformed.accepted() && malformed.nbt().isEmpty(),"Duplicate JSON key reached envelope");
        System.out.println("{\"test\":\"typed_json_envelope_planner_composition\",\"passed\":true,\"assertions\":"+assertions
            +",\"actual_definition_fixtures\":1,\"actual_player_save_fixtures\":0,\"synthetic_progress_used\":true,\"item_predicate_fixture\":true,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}

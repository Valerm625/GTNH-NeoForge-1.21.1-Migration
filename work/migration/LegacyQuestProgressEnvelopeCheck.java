import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;

/** Candidate-state checks with real NBT and fixed source outer routing methods.
 * No JSON, disk player saves, game hooks, task completion or reward calls. */
public final class LegacyQuestProgressEnvelopeCheck {
    static final UUID Q=new UUID(0,2), FULL=new UUID(4300463009256719073L,-7881005862838025770L);
    static final UUID ABSENT=new UUID(-8,17), NO_TASKS=new UUID(0,37);
    static final UUID A=new UUID(1,1), B=new UUID(2,2), C=new UUID(-1,-1);
    static final String RETRIEVAL="bq_standard:retrieval";
    static final TaskKey K7=new TaskKey(Q,7,RETRIEVAL), K2=new TaskKey(Q,2,RETRIEVAL);
    static final TaskKey KF=new TaskKey(FULL,0,RETRIEVAL), KA=new TaskKey(ABSENT,4,RETRIEVAL);
    static final Catalog CATALOG=new Catalog(List.of(Q,FULL,ABSENT,NO_TASKS),List.of(
            new Definition(K7,2),new Definition(K2,1),new Definition(KF,1),new Definition(KA,3)));
    static int assertions, sourceComparisons, binaryRoundTrips, rejectedDocuments;

    static void require(boolean condition,String reason) {
        assertions++; if (!condition) throw new AssertionError(reason);
    }
    static void illegal(Runnable operation,String reason) {
        try { operation.run(); throw new AssertionError(reason); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    static CompoundTag row(UUID player,int... values) {
        CompoundTag row=new CompoundTag(); row.putString("uuid",player.toString()); row.putIntArray("data",values); return row;
    }
    static CompoundTag done(UUID player,String text) {
        CompoundTag record=new CompoundTag(); record.putString("uuid",player.toString()); record.putString("note",text); return record;
    }
    static ListTag list(CompoundTag... records) {
        ListTag result=new ListTag(); for (CompoundTag record:records) result.add(record); return result;
    }
    static CompoundTag task(int index,List<CompoundTag> rows,UUID... completed) {
        CompoundTag result=new CompoundTag(); result.putInt("index",index); result.putString("taskID",RETRIEVAL);
        ListTag progress=new ListTag(); rows.forEach(progress::add); result.put("userProgress",progress);
        ListTag users=new ListTag(); for (UUID player:completed) users.add(StringTag.valueOf(player.toString()));
        result.put("completeUsers",users); return result;
    }
    static CompoundTag quest(UUID id,boolean legacy,CompoundTag[] completed,CompoundTag... tasks) {
        CompoundTag result=new CompoundTag();
        if (legacy) result.putInt("questID",(int)id.getLeastSignificantBits());
        else { result.putLong("questIDHigh",id.getMostSignificantBits()); result.putLong("questIDLow",id.getLeastSignificantBits()); }
        result.put("completed",list(completed)); result.put("tasks",list(tasks)); return result;
    }
    static CompoundTag document(CompoundTag... quests) {
        CompoundTag result=new CompoundTag(); result.put("questProgress",list(quests)); return result;
    }
    static CompoundTag valid() { return document(quest(Q,false,new CompoundTag[]{done(A,"new")},task(7,List.of(row(A,9,5)),C))); }
    static CompoundTag firstQuest(CompoundTag document) { return document.getList("questProgress",Tag.TAG_COMPOUND).getCompound(0); }
    static CompoundTag firstTask(CompoundTag document) { return firstQuest(document).getList("tasks",Tag.TAG_COMPOUND).getCompound(0); }
    static State prior() {
        return new State(Map.of(Q,Map.of(A,done(A,"previous A"),B,done(B,"previous B")),
                        ABSENT,Map.of(C,done(C,"absent quest"))),
                Map.of(K7,new LegacyRetrievalEngine.State(Map.of(A,List.of(8,4),B,List.of(1,2)),Set.of(A)),
                        K2,new LegacyRetrievalEngine.State(Map.of(B,List.of(6)),Set.of(B)),
                        KA,new LegacyRetrievalEngine.State(Map.of(C,List.of(2,3,4)),Set.of())));
    }
    static CompoundTag binary(CompoundTag input,boolean compressed) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if (compressed) NbtIo.writeCompressed(input,bytes);
        else NbtIo.write(input,new DataOutputStream(bytes));
        CompoundTag output=compressed
                ? NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()),NbtAccounter.create(8_000_000))
                : NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),NbtAccounter.create(8_000_000));
        binaryRoundTrips++; require(input.equals(output),"NBT binary changed input tree"); return output;
    }
    static State compare(CompoundTag input,State previous,boolean merge) {
        CompoundTag before=input.copy(); State previousCopy=new State(previous.questCompletions(),previous.tasks());
        ReadResult result=LegacyQuestProgressEnvelope.readLegacy(input,CATALOG,previous,merge);
        require(result.accepted() && result.problems().isEmpty(),"Recognized input rejected: "+result.problems());
        State expected=BqEnvelopeReference.read(input,CATALOG,previous,merge);
        sourceComparisons++; require(result.candidate().orElseThrow().equals(expected),"Fixed source routing mismatch");
        require(previous.equals(previousCopy),"Read mutated previous state");
        require(input.equals(before) && result.original().equals(before),"Read mutated or lost original tree");
        return result.candidate().orElseThrow();
    }
    static void reject(CompoundTag input,Catalog catalog,String code) throws Exception {
        CompoundTag before=binary(input,true); State previous=prior(); State previousCopy=prior();
        ReadResult result=LegacyQuestProgressEnvelope.readLegacy(input,catalog,previous,false);
        rejectedDocuments++;
        require(!result.accepted() && result.candidate().isEmpty(),"Invalid candidate accepted: "+code);
        require(result.problems().stream().anyMatch(problem->problem.code().equals(code)),"Missing diagnostic "+code+": "+result.problems());
        require(previous.equals(previousCopy) && input.equals(before),"Rejected transaction mutated input/previous");
        input.putString("caller_change","must not leak");
        require(result.original().equals(before),"Rejected original aliases supplied NBT");
        CompoundTag copy=result.original(); copy.putString("returned_change","must not leak");
        firstQuest(copy).putInt("nested_change",55);
        require(result.original().equals(before),"Rejected original accessor aliases stored NBT");
        require(binary(result.original(),false).equals(before),"Rejected original cannot roundtrip intact");
    }
    static void rejectedMutation(Consumer<CompoundTag> edit,String code) throws Exception {
        CompoundTag input=valid(); edit.accept(input); reject(input,CATALOG,code);
    }
    static CompoundTag opaque(UUID player) {
        CompoundTag result=done(player,"opaque");
        result.putByte("claimed",(byte)17); result.putString("timestamp","opaque even when a behavior getter expects numeric");
        result.putShort("short",(short)-12); result.putInt("int",-39); result.putLong("long",Long.MIN_VALUE);
        result.putFloat("float",1.25F); result.putDouble("double",-2.5);
        result.putByteArray("bytes",new byte[]{0,-1,127}); result.putIntArray("ints",new int[]{1,-2});
        result.putLongArray("longs",new long[]{Long.MAX_VALUE,Long.MIN_VALUE});
        CompoundTag child=new CompoundTag(); child.putString("nested","keep"); result.put("compound",child);
        ListTag values=new ListTag(); values.add(StringTag.valueOf("one")); values.add(StringTag.valueOf("two")); result.put("list",values);
        return result;
    }

    public static void main(String[] args) throws Exception {
        State previous=prior();
        CompoundTag canonical=valid();
        State replaced=compare(binary(canonical,true),previous,false);
        require(replaced.questCompletions().get(Q).keySet().equals(Set.of(A)),"Addressed quest completion was not replaced");
        require(replaced.questCompletions().get(ABSENT).equals(previous.questCompletions().get(ABSENT)),"merge=false cleared absent quest");
        require(replaced.tasks().get(K2).equals(previous.tasks().get(K2)),"merge=false cleared absent task in addressed quest");
        require(replaced.tasks().get(KA).equals(previous.tasks().get(KA)),"merge=false cleared task of absent quest");
        require(replaced.tasks().get(K7).progress().equals(Map.of(A,List.of(9,5))),"Matched task progress did not replace previous rows");
        require(replaced.tasks().get(K7).completed().equals(Set.of(C)),"Task completion inferred from quest completion/counters");
        require(!replaced.tasks().get(K7).progress().containsKey(C),"Completion-only task user gained fabricated progress");

        CompoundTag legacy=valid(); firstQuest(legacy).remove("questIDHigh"); firstQuest(legacy).remove("questIDLow"); firstQuest(legacy).putInt("questID",2);
        require(compare(binary(legacy,false),previous,false).equals(replaced),"Legacy Int identity differs from canonical UUID");
        CompoundTag full=document(quest(FULL,false,new CompoundTag[]{done(C,"full UUID")},task(0,List.of(row(B,42)))));
        require(compare(full,previous,false).tasks().get(KF).progress().get(B).equals(List.of(42)),"Signed full UUID routing lost bits");
        compare(document(quest(new UUID(0,Integer.MAX_VALUE),true,new CompoundTag[0])),State.empty(),false,
                new Catalog(List.of(new UUID(0,Integer.MAX_VALUE)),List.of()));
        compare(document(quest(new UUID(0,0),true,new CompoundTag[0])),State.empty(),false,
                new Catalog(List.of(new UUID(0,0)),List.of()));

        State merged=compare(canonical,previous,true);
        require(merged.questCompletions().get(Q).keySet().equals(Set.of(A,B)),"Quest completion merge dropped absent player");
        require(merged.tasks().get(K7).progress().get(B).equals(List.of(1,2)),"Task row merge dropped absent player");
        require(merged.tasks().get(K7).completed().equals(Set.of(A,C)),"Task completeUsers merge is not independent union");
        require(merged.questCompletions().get(Q).get(A).equals(done(A,"new")),"Quest complete compound not replaced");

        CompoundTag opaque=opaque(A);
        State opaquePrevious=new State(Map.of(Q,Map.of(A,opaque)),Map.of());
        State opaqueLoaded=compare(binary(document(quest(Q,false,new CompoundTag[]{opaque},task(7,List.of()))),true),State.empty(),false);
        require(opaqueLoaded.questCompletions().get(Q).get(A).equals(opaque),"Opaque completion tags changed");
        CompoundTag replacement=done(A,"replace whole compound"); replacement.put("claimed",new ListTag()); replacement.putIntArray("timestamp",new int[]{9,2});
        State opaqueReplaced=compare(document(quest(Q,false,new CompoundTag[]{replacement})),opaquePrevious,true);
        require(opaqueReplaced.questCompletions().get(Q).get(A).equals(replacement)
                && !opaqueReplaced.questCompletions().get(Q).get(A).contains("long"),"Opaque merge combined fields instead of replacing compound");
        CompoundTag duplicatePlayers=document(quest(Q,false,new CompoundTag[]{done(A,"first"),done(A,"last")}));
        require(compare(duplicatePlayers,previous,true).questCompletions().get(Q).get(A).getString("note").equals("last"),"Duplicate completion UUID order lost");

        CompoundTag duplicates=document(
                quest(Q,true,new CompoundTag[]{done(A,"first quest")},task(7,List.of(row(A,8,3)),A),task(7,List.of(row(B,5)),B)),
                quest(Q,false,new CompoundTag[]{done(C,"second quest")},task(2,List.of(row(C,6)),C)));
        State sequential=compare(duplicates,previous,false);
        require(sequential.questCompletions().get(Q).keySet().equals(Set.of(C)),"Repeated quest order lost");
        require(sequential.tasks().get(K7).progress().equals(Map.of(B,List.of(5,0))),"Repeated task order or absent retention lost");
        require(sequential.tasks().get(K2).progress().equals(Map.of(C,List.of(6))),"Integer task index treated as list position");
        compare(duplicates,previous,true);

        CompoundTag oldList=row(A); ListTag data=new ListTag(); data.add(IntTag.valueOf(9)); data.add(IntTag.valueOf(5)); data.add(IntTag.valueOf(11)); oldList.put("data",data);
        State shaped=compare(document(quest(Q,false,new CompoundTag[0],task(7,List.of(oldList,row(B,3))))),State.empty(),false);
        require(shaped.tasks().get(K7).progress().get(A).equals(List.of(9,5)),"Disk array/list prefix was reset rather than truncated");
        require(shaped.tasks().get(K7).progress().get(B).equals(List.of(3,0)),"Disk short row not zero padded");
        require(shaped.tasks().get(K7).completed().isEmpty(),"Completed set inferred from normalized rows");
        CompoundTag noTaskLists=valid(); firstTask(noTaskLists).remove("userProgress"); firstTask(noTaskLists).remove("completeUsers");
        require(compare(noTaskLists,previous,false).tasks().get(K7).equals(LegacyRetrievalEngine.State.empty()),"Missing inner lists did not clear matched task");
        CompoundTag noQuestLists=valid(); firstQuest(noQuestLists).remove("tasks"); firstQuest(noQuestLists).remove("completed");
        State noLists=compare(noQuestLists,previous,false);
        require(noLists.questCompletions().get(Q).isEmpty() && noLists.tasks().equals(previous.tasks()),"Missing quest lists cleared task store");
        require(compare(new CompoundTag(),previous,false).equals(previous),"Missing root progress reset database");
        require(compare(document(),previous,false).equals(previous),"Empty root progress reset database");
        compare(document(quest(NO_TASKS,false,new CompoundTag[]{done(A,"quest without retrieval")})),State.empty(),false);

        // Copies isolate original input, rejected input, constructor maps and accessor trees.
        CompoundTag mutable=opaque(A); Map<UUID,CompoundTag> mutableRows=new LinkedHashMap<>(); mutableRows.put(A,mutable);
        Map<UUID,Map<UUID,CompoundTag>> mutableQuests=new LinkedHashMap<>(); mutableQuests.put(Q,mutableRows);
        Map<TaskKey,LegacyRetrievalEngine.State> mutableTasks=new LinkedHashMap<>(previous.tasks());
        State isolated=new State(mutableQuests,mutableTasks); CompoundTag saved=mutable.copy();
        mutable.getCompound("compound").putInt("changed",1); mutableRows.clear(); mutableQuests.clear(); mutableTasks.clear();
        require(isolated.questCompletions().get(Q).get(A).equals(saved) && isolated.tasks().equals(previous.tasks()),"State constructor retained mutable inputs");
        isolated.questCompletions().get(Q).get(A).getCompound("compound").putInt("changed",2);
        require(isolated.questCompletions().get(Q).get(A).equals(saved),"State accessor leaked nested tag");
        try { isolated.tasks().clear(); throw new AssertionError("Mutable task map exposed"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        CompoundTag input=valid(); CompoundTag original=input.copy();
        ReadResult result=LegacyQuestProgressEnvelope.readLegacy(input,CATALOG,previous,false);
        firstQuest(input).getList("completed",Tag.TAG_COMPOUND).getCompound(0).putString("note","later input mutation");
        require(result.candidate().orElseThrow().equals(replaced) && result.original().equals(original),"Accepted result aliases caller input");
        firstQuest(result.original()).putString("other","returned original mutation");
        result.candidate().orElseThrow().questCompletions().get(Q).get(A).putString("note","accessor mutation");
        require(result.candidate().orElseThrow().equals(replaced) && result.original().equals(original),"Accepted accessors leaked mutable trees");

        rejectedMutation(x->x.putInt("schema_version",1),"unknown_field");
        rejectedMutation(x->x.put("future",opaque(C)),"unknown_field");
        rejectedMutation(x->x.putIntArray("questProgress",new int[]{1,2}),"unexpected_list_type");
        rejectedMutation(x->{ ListTag wrong=new ListTag(); wrong.add(StringTag.valueOf("record")); x.put("questProgress",wrong); },"unexpected_list_type");
        rejectedMutation(x->firstQuest(x).putString("future","retain"),"unknown_field");
        rejectedMutation(x->firstQuest(x).remove("questIDHigh"),"incomplete_uuid_pair");
        rejectedMutation(x->firstQuest(x).putInt("questIDHigh",0),"expected_long_uuid_pair");
        rejectedMutation(x->firstQuest(x).putString("questIDLow","2"),"expected_long_uuid_pair");
        rejectedMutation(x->firstQuest(x).putInt("questID",2),"ambiguous_quest_identity");
        rejectedMutation(x->{ firstQuest(x).remove("questIDHigh"); firstQuest(x).remove("questIDLow"); },"quest_identity_missing");
        rejectedMutation(x->{ firstQuest(x).remove("questIDHigh"); firstQuest(x).remove("questIDLow"); firstQuest(x).putInt("questID",-1); },"unsupported_legacy_quest_id");
        rejectedMutation(x->{ firstQuest(x).remove("questIDHigh"); firstQuest(x).remove("questIDLow"); firstQuest(x).putLong("questID",2); },"unsupported_legacy_quest_id");
        rejectedMutation(x->firstQuest(x).putLong("questIDLow",9000),"quest_definition_missing");
        rejectedMutation(x->firstQuest(x).putString("completed","wrong"),"unexpected_list_type");
        rejectedMutation(x->firstQuest(x).putInt("tasks",7),"unexpected_list_type");
        rejectedMutation(x->firstQuest(x).getList("completed",Tag.TAG_COMPOUND).getCompound(0).remove("uuid"),"expected_string_uuid");
        rejectedMutation(x->firstQuest(x).getList("completed",Tag.TAG_COMPOUND).getCompound(0).putString("uuid","invalid"),"invalid_uuid");
        rejectedMutation(x->firstQuest(x).getList("completed",Tag.TAG_COMPOUND).getCompound(0).putLong("uuid",1),"expected_string_uuid");
        rejectedMutation(x->firstTask(x).remove("index"),"expected_nonnegative_int_index");
        rejectedMutation(x->firstTask(x).putInt("index",-1),"expected_nonnegative_int_index");
        rejectedMutation(x->firstTask(x).putLong("index",7),"expected_nonnegative_int_index");
        rejectedMutation(x->firstTask(x).putInt("index",6),"task_definition_missing");
        rejectedMutation(x->firstTask(x).remove("taskID"),"expected_string_factory");
        rejectedMutation(x->firstTask(x).putInt("taskID",7),"expected_string_factory");
        rejectedMutation(x->firstTask(x).putString("taskID","bq_standard:crafting"),"factory_mismatch");
        rejectedMutation(x->{ firstTask(x).putString("taskID","betterquesting:placeholder"); firstTask(x).put("orig_prog",opaque(A)); },"placeholder_not_supported");
        rejectedMutation(x->firstTask(x).put("unknown_task_data",opaque(B)),"unknown_field");
        rejectedMutation(x->firstTask(x).getList("userProgress",Tag.TAG_COMPOUND).getCompound(0).putIntArray("data",new int[]{9,5,-1}),"unsupported_negative_progress");
        rejectedMutation(x->firstTask(x).getList("userProgress",Tag.TAG_COMPOUND).getCompound(0).putString("uuid","broken"),"invalid_uuid");
        TaskKey other=new TaskKey(Q,7,"bq_standard:crafting");
        CompoundTag wrongFactory=valid(); firstTask(wrongFactory).putString("taskID",other.factoryId());
        reject(wrongFactory,new Catalog(List.of(Q),List.of(new Definition(other,2))),"unsupported_task_factory");
        CompoundTag partial=document(firstQuest(valid()).copy(),quest(FULL,false,new CompoundTag[]{done(B,"valid second")},task(0,List.of(row(B,11)))));
        partial.getList("questProgress",Tag.TAG_COMPOUND).getCompound(1).putInt("future_second_record",12);
        reject(partial,CATALOG,"unknown_field");
        CompoundTag badMiddle=valid(); firstQuest(badMiddle).getList("tasks",Tag.TAG_COMPOUND).add(task(100,List.of(row(B,9))));
        firstQuest(badMiddle).getList("tasks",Tag.TAG_COMPOUND).add(task(2,List.of(row(C,8))));
        reject(badMiddle,CATALOG,"task_definition_missing");
        illegal(()->new Catalog(List.of(Q),List.of(new Definition(K7,2),new Definition(other,2))),"Duplicate catalog index accepted");
        illegal(()->new Catalog(List.of(Q),List.of(new Definition(KF,2))),"Catalog task without quest accepted");
        illegal(()->new Definition(K7,0),"Unsupported zero requirement count accepted");

        // Ordered generated documents exercise only the recognized source domain.
        Random random=new Random(0x71D4B28L); UUID[] quests={Q,FULL,ABSENT,NO_TASKS}, users={A,B,C};
        for (int iteration=0;iteration<500;iteration++) {
            List<CompoundTag> records=new ArrayList<>();
            for (int n=0;n<random.nextInt(8);n++) {
                UUID id=quests[random.nextInt(quests.length)]; List<CompoundTag> completion=new ArrayList<>();
                for (int j=0;j<random.nextInt(5);j++) {
                    CompoundTag entry=done(users[random.nextInt(users.length)],"generated-"+n+"-"+j);
                    entry.putIntArray("opaque",new int[]{iteration,j,-1}); completion.add(entry);
                }
                List<CompoundTag> tasks=new ArrayList<>();
                for (int j=0;j<random.nextInt(6) && !id.equals(NO_TASKS);j++) {
                    int index=id.equals(Q)?(random.nextBoolean()?7:2):(id.equals(FULL)?0:4);
                    List<CompoundTag> rows=new ArrayList<>();
                    for (int r=0;r<random.nextInt(5);r++) {
                        int[] values=new int[random.nextInt(5)]; for (int v=0;v<values.length;v++) values[v]=random.nextInt(20);
                        CompoundTag entry=row(users[random.nextInt(users.length)],values);
                        if (random.nextBoolean()) { ListTag old=new ListTag(); for (int value:values) old.add(IntTag.valueOf(value)); entry.put("data",old); }
                        rows.add(entry);
                    }
                    CompoundTag value=task(index,rows,random.nextBoolean()?new UUID[]{users[random.nextInt(users.length)]}:new UUID[0]);
                    if (random.nextInt(5)==0) value.remove("completeUsers");
                    if (random.nextInt(5)==0) value.remove("userProgress");
                    tasks.add(value);
                }
                CompoundTag record=quest(id,id.getMostSignificantBits()==0 && random.nextBoolean(),completion.toArray(CompoundTag[]::new),tasks.toArray(CompoundTag[]::new));
                if (random.nextInt(5)==0) record.remove("completed");
                if (random.nextInt(5)==0) record.remove("tasks");
                records.add(record);
            }
            CompoundTag generated=binary(document(records.toArray(CompoundTag[]::new)),iteration%2==0);
            compare(generated,previous,false); compare(generated,previous,true);
        }
        System.out.println("{\"passed\":true,\"assertions\":"+assertions+",\"source_comparisons\":"+sourceComparisons
                +",\"binary_round_trips\":"+binaryRoundTrips+",\"rejected_documents\":"+rejectedDocuments
                +",\"actual_minecraft_nbt\":true,\"source_routing_methods_executed\":true,\"accepted_candidate_only\":true"
                +",\"runtime_integrated\":false,\"file_storage_integrated\":false,\"json_import_supported\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }

    static State compare(CompoundTag input,State previous,boolean merge,Catalog catalog) {
        ReadResult result=LegacyQuestProgressEnvelope.readLegacy(input,catalog,previous,merge);
        require(result.accepted(),"Boundary identity rejected");
        State expected=BqEnvelopeReference.read(input,catalog,previous,merge);
        sourceComparisons++; require(result.candidate().orElseThrow().equals(expected),"Boundary identity routing mismatch");
        return result.candidate().orElseThrow();
    }
}

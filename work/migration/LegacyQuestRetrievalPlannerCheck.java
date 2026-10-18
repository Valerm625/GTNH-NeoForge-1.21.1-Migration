import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;

/** Differential test of the extracted BQ quest loop and storage ordering.
 * String keys are immutable; each side receives separately copied inputs.
 */
public final class LegacyQuestRetrievalPlannerCheck {
    static final UUID A=new UUID(1,1), B=new UUID(2,2), OFFLINE=new UUID(3,3), OUTSIDE=new UUID(4,4);
    static final UUID QUEST=new UUID(0,2);
    static final Gate OPEN=new Gate(true,true,true,false);
    static int assertions, comparisons;
    static void require(boolean value,String message) {
        assertions++;
        if(!value) throw new AssertionError(message);
    }
    static Config<String> config(boolean consume,int[] counts,String... items) {
        List<Requirement<String>> requirements=new ArrayList<>();
        for(int i=0;i<counts.length;i++) {
            String item=items[i];
            requirements.add(new Requirement<>(counts[i],key->item.equals("*") || item.equals(key)));
        }
        return new Config<>(requirements,consume,false,false,false);
    }
    static Quest<String> quest(List<Task<String>> tasks) {
        return new Quest<>(QUEST,tasks,"AND",-1,false,false,false,false);
    }
    static Slot<String> slot(int index,String item,int count) { return new Slot<>(index,item,count); }
    static Context<String> context(int logs) {
        Map<UUID,List<Slot<String>>> inventories=new LinkedHashMap<>();
        inventories.put(A,List.of(slot(17,"gravel",9),slot(22,"log",logs)));
        inventories.put(B,List.of());
        return new Context<>(A,List.of(A,B,OFFLINE),inventories);
    }
    static Map<Integer,State> copyStates(Map<Integer,State> states) {
        Map<Integer,State> copy=new LinkedHashMap<>();
        states.forEach((index,state)->{
            Map<UUID,List<Integer>> rows=new LinkedHashMap<>();
            state.progress().forEach((id,row)->rows.put(id,new ArrayList<>(row)));
            copy.put(index,new State(rows,new LinkedHashSet<>(state.completed())));
        });
        return copy;
    }
    static Context<String> copyContext(Context<String> context) {
        Map<UUID,List<Slot<String>>> inventories=new LinkedHashMap<>();
        context.inventories().forEach((id,slots)->{
            List<Slot<String>> copy=new ArrayList<>();
            for(Slot<String> slot:slots) copy.add(new Slot<>(slot.index(),slot.item(),slot.count()));
            inventories.put(id,copy);
        });
        return new Context<>(context.actor(),new ArrayList<>(context.allMembers()),inventories);
    }
    static Plan<String> check(Quest<String> quest,Map<Integer,State> states,Context<String> context,Gate gate) {
        Map<Integer,State> originalStates=copyStates(states);
        Context<String> originalContext=copyContext(context);
        Map<Integer,State> actualStates=copyStates(states), referenceStates=copyStates(states);
        Context<String> actualContext=copyContext(context), referenceContext=copyContext(context);
        Plan<String> actual=LegacyQuestRetrievalPlanner.plan(quest,actualStates,actualContext,gate);
        require(actualStates.equals(originalStates) && actualContext.equals(originalContext),"Planner mutated its input snapshot");
        Plan<String> expected=BqQuestRetrievalReference.run(quest,referenceStates,referenceContext,gate);
        require(referenceStates.equals(originalStates) && referenceContext.equals(originalContext),"Oracle mutated its input snapshot");
        require(states.equals(originalStates) && context.equals(originalContext),"Shared caller input changed");
        comparisons++;
        require(actual.equals(expected),"Source loop mismatch #"+comparisons+"\nactual="+actual+"\nsource="+expected);
        return actual;
    }
    static void rejects(Quest<String> quest,String code,Map<Integer,State> state) {
        Map<Integer,State> before=copyStates(state);
        Context<String> inventory=context(5), beforeInventory=copyContext(inventory);
        try {
            LegacyQuestRetrievalPlanner.plan(quest,state,inventory,OPEN);
            throw new AssertionError("Unsupported declaration accepted: "+code);
        } catch(UnsupportedQuestException error) {
            require(error.declaration()==quest,"Rejected declaration was discarded/replaced");
            require(error.problems().stream().anyMatch(p->p.code().equals(code)),"Missing rejection reason "+code);
        }
        require(state.equals(before) && inventory.equals(beforeInventory),"Rejected declaration changed input");
    }
    public static void main(String[] args) {
        Config<String> collect=config(false,new int[]{9,5},"gravel","log");
        Config<String> deliver=config(true,new int[]{2},"log");
        Quest<String> normal=quest(List.of(Task.retrieval(0,collect),Task.retrieval(1,deliver)));
        Context<String> five=context(5);
        Plan<String> enough=check(normal,Map.of(),five,OPEN);
        require(enough.allTasksCompleteForActor() && enough.questDirty(),"Five logs did not satisfy both tasks");
        require(enough.afterInventories().get(A).get(1).count()==3,"Five logs must leave three");
        require(enough.detections().stream().map(Detection::taskIndex).toList().equals(List.of(0,1)),"Wrong detection order");
        require(enough.afterStates().get(0).completed().contains(OFFLINE)
                && enough.afterStates().get(1).progress().get(OFFLINE).equals(List.of(2)),"Offline task state lost");
        require(enough.consumed().equals(List.of(new Consumption<>(A,22,"log",2))),"Wrong combined consumption plan");
        require(enough.beforeInventories().equals(five.inventories()),"Plan omitted precondition inventory snapshot");

        Quest<String> reversedInput=quest(List.of(Task.retrieval(1,deliver),Task.retrieval(0,collect)));
        require(check(reversedInput,Map.of(),five,OPEN).equals(enough),"Insertion order overrode source indices");
        Quest<String> reversedIndices=quest(List.of(Task.retrieval(0,deliver),Task.retrieval(1,collect)));
        Plan<String> reversed=check(reversedIndices,Map.of(),five,OPEN);
        require(!reversed.allTasksCompleteForActor() && reversed.afterStates().get(0).completed().contains(A),
                "Reversing task indices did not change behavior");
        require(reversed.afterStates().get(1).progress().get(A).equals(List.of(9,3)),"Later task did not see consumed inventory");

        Plan<String> repeat=check(normal,enough.afterStates(),new Context<>(A,five.allMembers(),enough.afterInventories()),OPEN);
        require(repeat.detections().isEmpty() && repeat.consumed().isEmpty(),"Repeated submission executed completed tasks");
        require(repeat.afterStates().equals(enough.afterStates()) && repeat.afterInventories().equals(enough.afterInventories()),
                "Repeat submission changed completed state/inventory");
        Plan<String> questComplete=check(normal,enough.afterStates(),five,new Gate(true,true,true,true));
        require(!questComplete.evaluated() && !questComplete.questDirty(),"Completed nonrepeat quest was evaluated");

        Plan<String> insufficient=check(normal,Map.of(),context(1),OPEN);
        require(insufficient.detections().size()==2 && insufficient.afterStates().get(1).progress().get(A).equals(List.of(1)),
                "Incomplete first task incorrectly stopped a later delivery");
        require(!insufficient.questDirty() && insufficient.detections().stream().allMatch(d->d.result().partyDirty()),
                "Partial task dirty signal was confused with quest-loop dirty signal");
        Plan<String> rest=check(normal,insufficient.afterStates(),context(5),OPEN);
        require(rest.allTasksCompleteForActor() && rest.afterInventories().get(A).get(1).count()==4,
                "Per-task partial delivery state did not survive the next submit");

        Quest<String> sparse=quest(List.of(Task.retrieval(Integer.MAX_VALUE,deliver),Task.retrieval(37,collect)));
        State detached=new State(Map.of(OUTSIDE,List.of(8,7)),Set.of(OUTSIDE));
        Map<Integer,State> sparseSaved=Map.of(19,detached);
        Plan<String> sparsePlan=check(sparse,sparseSaved,five,OPEN);
        require(sparsePlan.detections().stream().map(Detection::taskIndex).toList().equals(List.of(37,Integer.MAX_VALUE)),
                "Sparse task indices were compacted or reordered");
        require(sparsePlan.afterStates().get(19).equals(detached),"Nonconfigured saved task state was discarded");

        Map<UUID,List<Slot<String>>> inventories=new LinkedHashMap<>();
        inventories.put(A,List.of(slot(3,"log",64))); inventories.put(B,List.of(slot(77,"log",2)));
        Context<String> actorB=new Context<>(B,List.of(OFFLINE,A,B),inventories);
        Plan<String> payer=check(quest(List.of(Task.retrieval(9,deliver))),Map.of(),actorB,OPEN);
        require(payer.consumed().equals(List.of(new Consumption<>(B,77,"log",2)))
                && payer.afterInventories().get(A).getFirst().count()==64,"Actor identity/index did not control consumption");
        require(payer.afterStates().get(9).completed().containsAll(actorB.allMembers()),"Offline/reordered members lost completion");

        for(Gate gate:List.of(new Gate(false,true,true,false),new Gate(true,false,true,false),new Gate(true,true,false,false))) {
            Plan<String> blocked=check(normal,Map.of(),five,gate);
            require(!blocked.evaluated() && blocked.detections().isEmpty() && blocked.consumed().isEmpty(),"Entry check was bypassed");
        }
        AtomicInteger called=new AtomicInteger();
        Config<String> mustNotRun=new Config<>(List.of(new Requirement<>(1,item->{called.incrementAndGet(); return true;})),false,false,false,false);
        Task<String> unknown=new Task<>(1,"bq_standard:crafting",null,false);
        Quest<String> unsupported=quest(List.of(Task.retrieval(0,mustNotRun),unknown));
        rejects(unsupported,"unsupported_task_type",Map.of(1,new State(Map.of(),Set.of(A))));
        require(called.get()==0,"Preflight skipped an already complete unknown task or executed an earlier predicate");
        rejects(quest(List.of(new Task<>(0,LegacyQuestRetrievalPlanner.RETRIEVAL_TYPE,collect,true))),"unsupported_ignored_task",Map.of());
        rejects(quest(List.of(new Task<>(0,LegacyQuestRetrievalPlanner.RETRIEVAL_TYPE,null,false))),"missing_retrieval_configuration",Map.of());
        rejects(quest(List.of(Task.retrieval(0,collect),Task.retrieval(0,deliver))),"duplicate_task_index",Map.of());
        rejects(quest(List.of()),"unsupported_empty_quest",Map.of());
        rejects(new Quest<>(QUEST,normal.tasks(),"OR",-1,false,false,false,false),"unsupported_task_logic",Map.of());
        rejects(new Quest<>(QUEST,normal.tasks(),"AND",0,false,false,false,false),"unsupported_repeat",Map.of());
        rejects(new Quest<>(QUEST,normal.tasks(),"AND",-1,true,false,false,false),"unsupported_simultaneous",Map.of());
        rejects(new Quest<>(QUEST,normal.tasks(),"AND",-1,false,true,false,false),"unsupported_global",Map.of());
        var sharedFlag=new Quest<>(QUEST,normal.tasks(),"AND",-1,false,false,true,false);
        require(check(sharedFlag,Map.of(),five,OPEN).equals(check(normal,Map.of(),five,OPEN)),"Inactive globalShare changed nonglobal detection");
        rejects(new Quest<>(QUEST,normal.tasks(),"AND",-1,false,true,true,false),"unsupported_global",Map.of());
        rejects(new Quest<>(QUEST,normal.tasks(),"AND",-1,false,false,false,true),"unsupported_edit_mode",Map.of());

        Config<String> throwing=new Config<>(List.of(new Requirement<>(1,item->{throw new IllegalStateException("fixture failure");})),false,false,false,false);
        Quest<String> failsAfterConsumption=quest(List.of(Task.retrieval(0,deliver),Task.retrieval(1,throwing)));
        Map<Integer,State> saved=new LinkedHashMap<>();
        Map<Integer,State> savedSnapshot=copyStates(saved); Context<String> inventorySnapshot=copyContext(five);
        boolean failed=false;
        try { LegacyQuestRetrievalPlanner.plan(failsAfterConsumption,saved,five,OPEN); }
        catch(IllegalStateException expected) { failed=true; }
        require(failed && saved.equals(savedSnapshot) && five.equals(inventorySnapshot),"Failed later task exposed partial input mutations");
        boolean immutable=false;
        try { enough.afterStates().clear(); } catch(UnsupportedOperationException expected) { immutable=true; }
        require(immutable,"Plan state map is mutable");
        immutable=false;
        try { enough.afterInventories().get(A).clear(); } catch(UnsupportedOperationException expected) { immutable=true; }
        require(immutable,"Plan inventory list is mutable");

        Random random=new Random(3889210202L);
        for(int trial=0;trial<400;trial++) {
            List<Task<String>> tasks=new ArrayList<>(); Map<Integer,State> history=new LinkedHashMap<>();
            int taskCount=1+random.nextInt(4);
            for(int task=0;task<taskCount;task++) {
                int index=7+task*31; boolean consuming=random.nextBoolean(); int n=consuming?1:1+random.nextInt(3);
                int[] counts=new int[n]; String[] matches=new String[n];
                for(int i=0;i<n;i++) { counts[i]=1+random.nextInt(7); matches[i]=List.of("log","gravel","*").get(random.nextInt(3)); }
                tasks.add(Task.retrieval(index,config(consuming,counts,matches)));
                if(random.nextBoolean()) {
                    Map<UUID,List<Integer>> rows=new LinkedHashMap<>();
                    for(UUID id:List.of(A,B,OFFLINE)) {
                        if(random.nextBoolean()) {
                            List<Integer> row=new ArrayList<>();
                            for(int count:counts) row.add(random.nextInt(count+3));
                            rows.put(id,row);
                        }
                    }
                    history.put(index,new State(rows,random.nextInt(6)==0?Set.of(B):Set.of()));
                }
            }
            Collections.shuffle(tasks,random); Quest<String> declaration=quest(tasks);
            for(int round=0;round<6;round++) {
                UUID actor=random.nextBoolean()?A:B;
                List<UUID> members=new ArrayList<>(List.of(A,B,OFFLINE)); Collections.shuffle(members,random);
                List<UUID> online=new ArrayList<>(List.of(A,B)); Collections.shuffle(online,random);
                Map<UUID,List<Slot<String>>> snapshots=new LinkedHashMap<>();
                for(UUID id:online) {
                    if(!id.equals(actor) && random.nextBoolean()) continue;
                    List<Slot<String>> slots=new ArrayList<>();
                    for(int i=0;i<3;i++) slots.add(slot(8+i*17,random.nextBoolean()?"log":"gravel",random.nextInt(8)));
                    snapshots.put(id,slots);
                }
                Gate gate=random.nextInt(10)==0?new Gate(true,true,true,true):OPEN;
                Plan<String> plan=check(declaration,history,new Context<>(actor,members,snapshots),gate);
                history=plan.afterStates();
            }
        }
        System.out.println("{\"test\":\"source_differential_legacy_quest_retrieval_planner\",\"passed\":true,\"assertions\":"+assertions
            +",\"source_comparisons\":"+comparisons+",\"source_quest_detect_executed\":true,\"source_database_order_executed\":true"
            +",\"independent_input_snapshots_verified\":true,\"atomic_calculation_only\":true,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}

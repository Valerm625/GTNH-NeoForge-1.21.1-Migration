import java.util.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;

/** Differential tests execute the pinned original Detector via generated
 * fixture classes. This checks counters/plans, not game inventory adapters.
 */
public class LegacyRetrievalEngineCheck {
    private static final UUID A=new UUID(1,1), B=new UUID(2,2), OFFLINE=new UUID(3,3), OUTSIDE=new UUID(4,4);
    private static int assertions, comparisons;
    static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    static Config<String> config(boolean consume, int[] counts, String... matches) {
        List<Requirement<String>> requirements=new ArrayList<>();
        for (int i=0;i<counts.length;i++) {
            String match=matches[i];
            requirements.add(new Requirement<>(counts[i], item -> match.equals("*") || match.equals(item)));
        }
        return new Config<>(requirements,consume,false,false,false);
    }
    static Slot<String> slot(int index,String item,int count) { return new Slot<>(index,item,count); }
    static Context<String> context(List<Slot<String>> actor,List<Slot<String>> teammate) {
        Map<UUID,List<Slot<String>>> inventories=new LinkedHashMap<>();
        inventories.put(A,actor); inventories.put(B,teammate);
        return new Context<>(A,List.of(A,B,OFFLINE),inventories);
    }
    static Result<String> check(Config<String> config,State state,Context<String> context,boolean manual) {
        Result<String> actual=manual ? LegacyRetrievalEngine.detectManually(config,state,context)
                : LegacyRetrievalEngine.observeInventory(config,state,context);
        Result<String> reference=BqRetrievalReference.run(config,state,context,manual);
        comparisons++;
        require(actual.equals(reference),"Source mismatch #"+comparisons+"\nactual="+actual+"\nsource="+reference);
        return actual;
    }
    static void rejects(Runnable action,String label) {
        boolean rejected=false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected=true; }
        require(rejected,"Accepted unreviewed mode: "+label);
    }
    public static void main(String[] args) {
        Config<String> collect=config(false,new int[]{9,5},"gravel","log");
        Config<String> deliver=config(true,new int[]{2},"log");
        Context<String> initial=context(List.of(slot(0,"gravel",9),slot(1,"log",5)),List.of());
        Result<String> seen=check(collect,State.empty(),initial,true);
        require(seen.state().completed().containsAll(initial.allMembers()),"Initial retrieval did not complete everyone");
        require(seen.state().progress().get(A).equals(List.of(9,5)),"Initial requirements incorrect");
        require(seen.consumed().isEmpty(),"Observation consumed items");
        Result<String> paid=check(deliver,State.empty(),new Context<>(A,initial.allMembers(),seen.inventories()),true);
        require(paid.inventories().get(A).get(1).count()==3,"Five logs must leave three after ordered detect/deliver");
        require(paid.inventories().get(B).isEmpty(),"Empty teammate inventory changed");
        require(paid.state().progress().get(OFFLINE).equals(List.of(2)),"Offline member did not receive credit");
        require(paid.consumed().equals(List.of(new Consumption<>(A,1,"log",2))),"Wrong consumption plan");
        require(initial.inventories().get(A).get(1).count()==5,"Pure engine mutated input inventory");
        Result<String> duplicate=check(deliver,paid.state(),new Context<>(A,initial.allMembers(),paid.inventories()),true);
        require(duplicate.consumed().isEmpty() && duplicate.inventories().equals(paid.inventories()),"Repeat submission consumed twice");

        Config<String> overlap=config(false,new int[]{5,5},"log","log");
        Context<String> fiveLogs=context(List.of(slot(0,"log",5)),List.of());
        Result<String> first=check(overlap,State.empty(),fiveLogs,true);
        require(first.state().progress().get(A).equals(List.of(5,0)),"One slot double-counted within one detection");
        require(first.state().completed().isEmpty(),"Overlapping requirements completed too early");
        Result<String> second=check(overlap,first.state(),fiveLogs,true);
        require(second.state().progress().get(A).equals(List.of(5,5)),"Per-requirement latch lost across detections");
        require(second.state().completed().containsAll(fiveLogs.allMembers()),"Latched overlap did not finish");

        Config<String> one=config(false,new int[]{5},"log");
        Result<String> partial=check(one,State.empty(),context(List.of(slot(0,"log",3)),List.of()),false);
        require(partial.state().progress().get(A).equals(List.of(3)),"Partial observation incorrect");
        Result<String> empty=check(one,partial.state(),context(List.of(slot(0,null,0)),List.of()),false);
        require(empty.state().progress().get(A).equals(List.of(0)),"Cleared inventory did not reset partial progress");
        State split=new State(Map.of(A,List.of(5,0),B,List.of(0,5),OUTSIDE,List.of(2,3)),Set.of());
        Result<String> notCombined=check(overlap,split,context(List.of(),List.of()),true);
        require(notCombined.state().completed().isEmpty(),"Different players' history was stitched into a complete row");
        require(notCombined.state().progress().get(OUTSIDE).equals(List.of(2,3)),"Nonparticipant state was changed");

        Result<String> team=check(one,State.empty(),context(List.of(slot(0,"log",2)),List.of(slot(0,"log",3))),false);
        require(team.state().progress().get(A).equals(List.of(5)) && team.state().progress().get(B).equals(List.of(5)),
                "Online inventories were not combined into each participant row");
        Result<String> noActorItems=check(deliver,State.empty(),context(List.of(),List.of(slot(0,"log",64))),true);
        require(noActorItems.consumed().isEmpty() && noActorItems.state().completed().isEmpty(),"Manual delivery took teammate items");
        Result<String> actorPays=check(deliver,State.empty(),context(List.of(slot(0,"log",2)),List.of(slot(0,"log",64))),true);
        require(actorPays.inventories().get(A).getFirst().count()==0
                && actorPays.inventories().get(B).getFirst().count()==64
                && actorPays.consumed().equals(List.of(new Consumption<>(A,0,"log",2))),
                "Successful delivery must consume only the actor's inventory");
        Result<String> automatic=check(deliver,State.empty(),fiveLogs,false);
        require(automatic.state().equals(State.empty()) && automatic.consumed().isEmpty(),"Manual-consuming task auto-consumed");
        Result<String> half=check(deliver,State.empty(),context(List.of(slot(0,"log",1)),List.of()),true);
        Result<String> rest=check(deliver,half.state(),context(List.of(slot(0,"log",1)),List.of()),true);
        require(rest.state().completed().containsAll(fiveLogs.allMembers()),"Manual partial consumption did not accumulate");
        State completedActor=new State(Map.of(A,List.of(5),B,List.of(2)),Set.of(A));
        require(check(one,completedActor,context(List.of(),List.of()),true).state().equals(completedActor),"Completed actor was re-evaluated");

        // Persisted counts of other members may exceed the target after a
        // runner consumes. Do not silently clamp away source state.
        State uneven=new State(Map.of(A,List.of(0),B,List.of(9)),Set.of());
        Result<String> over=check(deliver,uneven,fiveLogs,true);
        require(over.state().progress().get(B).equals(List.of(11)),"Source's broadcast increment was incorrectly clamped");
        State changedShape=new State(Map.of(A,List.of(1,2)),Set.of());
        check(one,changedShape,context(List.of(slot(0,"log",1)),List.of()),true);
        Context<String> reordered=new Context<>(A,List.of(OFFLINE,B,A),
                new LinkedHashMap<>(Map.of(A,List.of(slot(7,"log",2)),B,List.of(slot(9,"log",64)))));
        Result<String> reorderedPaid=check(deliver,State.empty(),reordered,true);
        require(reorderedPaid.consumed().equals(List.of(new Consumption<>(A,7,"log",2))),
                "Actor consumption must not depend on participant ordering or contiguous slot keys");

        rejects(() -> new Config<>(one.requirements(),false,false,true,false),"groupDetect");
        rejects(() -> new Config<>(one.requirements(),false,false,false,true),"OR requirements");
        rejects(() -> new Config<>(one.requirements(),true,true,false,false),"auto consumption");
        rejects(() -> new Config<>(overlap.requirements(),true,false,false,false),"multi-requirement consumption");
        rejects(() -> new Config<String>(List.of(),false,false,false,false),"empty requirements");
        rejects(() -> new Requirement<String>(0,item->true),"zero requirement");

        // Deliberate fail-closed input boundary: legacy int wraparound is not
        // represented as a supported saved state by this unintegrated planner.
        boolean overflowRejected=false;
        State nearOverflow=new State(Map.of(A,List.of(0),B,List.of(Integer.MAX_VALUE)),Set.of());
        try { LegacyRetrievalEngine.detectManually(deliver,nearOverflow,fiveLogs); }
        catch (ArithmeticException expected) { overflowRejected=true; }
        require(overflowRejected,"Counter overflow must reject the whole plan");
        require(nearOverflow.progress().get(B).equals(List.of(Integer.MAX_VALUE))
                && fiveLogs.inventories().get(A).getFirst().count()==5,
                "Rejected plan mutated caller state or inventory");

        Random random=new Random(3889210136L);
        for (int trial=0;trial<1200;trial++) {
            boolean consume=random.nextBoolean();
            int n=consume?1:1+random.nextInt(3);
            int[] counts=new int[n]; String[] predicates=new String[n];
            for (int i=0;i<n;i++) { counts[i]=1+random.nextInt(7); predicates[i]=List.of("log","gravel","*").get(random.nextInt(3)); }
            Config<String> cfg=config(consume,counts,predicates);
            Map<UUID,List<Integer>> history=new LinkedHashMap<>();
            for (UUID id:List.of(A,B,OFFLINE)) {
                if (random.nextBoolean()) {
                    List<Integer> row=new ArrayList<>();
                    for (int count:counts) row.add(random.nextInt(count+3));
                    history.put(id,row);
                }
            }
            State state=new State(history,random.nextInt(12)==0?Set.of(A):Set.of());
            for (int observation=0;observation<8;observation++) {
                List<Slot<String>> a=new ArrayList<>(),b=new ArrayList<>();
                for (int i=0;i<3;i++) {
                    a.add(slot(i,random.nextBoolean()?"log":"gravel",random.nextInt(5)));
                    b.add(slot(i,random.nextBoolean()?"log":"gravel",random.nextInt(5)));
                }
                Context<String> ctx=context(a,random.nextBoolean()?b:List.of());
                state=check(cfg,state,ctx,random.nextBoolean()).state();
            }
        }
        System.out.println("{\"test\":\"source_differential_legacy_retrieval_engine\",\"passed\":true,\"assertions\":"+assertions
                +",\"source_comparisons\":"+comparisons+",\"source_algorithm_executed\":true,\"predicate_fixture\":true,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}

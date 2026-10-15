import java.util.*;
import org.gtnhmigration.compat.LegacyRetrievalCounter;
public final class LegacyRetrievalCounterCheck {
    private static final UUID A=new UUID(1,1),B=new UUID(2,2);
    static long reference(long previous,long target,Map<UUID,? extends Iterable<Integer>> items) {
        if(previous>=target)return target;
        long progress=0;
        for(Iterable<Integer> stacks:items.values())for(int stack:stacks)progress+=Math.min(target-progress,stack);
        return progress;
    }
    static void eq(long actual,long expected){if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        eq(LegacyRetrievalCounter.next(0,8,Map.of(A,List.of(3),B,List.of(5))),8);
        eq(LegacyRetrievalCounter.next(7,8,Map.of(A,List.of(2))),2);
        eq(LegacyRetrievalCounter.next(7,8,Map.of()),0);
        eq(LegacyRetrievalCounter.next(8,8,Map.of()),8);
        eq(LegacyRetrievalCounter.next(0,8,Map.of(A,List.of(64))),8);
        long partial=0;for(int i=0;i<50;i++){partial=LegacyRetrievalCounter.next(partial,8,Map.of(A,List.of(3)));eq(partial,3);}
        eq(LegacyRetrievalCounter.next(0,Long.MAX_VALUE,Map.of(A,List.of(Integer.MAX_VALUE,Integer.MAX_VALUE))),4294967294L);
        boolean bad=false;try{LegacyRetrievalCounter.next(0,0,Map.of());}catch(IllegalArgumentException e){bad=true;}if(!bad)throw new AssertionError("Invalid target allowed");
        Random rng=new Random(38210136);int checked=0;
        for(int trial=0;trial<500;trial++){
            long target=rng.nextInt(63)+1,previous=0;
            for(int step=0;step<20;step++){
                Map<UUID,List<Integer>> inventories=new LinkedHashMap<>();
                inventories.put(A,List.of(rng.nextInt(6),rng.nextInt(6)));
                if(rng.nextBoolean())inventories.put(B,List.of(rng.nextInt(6),rng.nextInt(6)));
                long expected=reference(previous,target,inventories);
                long actual=LegacyRetrievalCounter.next(previous,target,inventories);eq(actual,expected);previous=actual;checked++;
            }
        }
        System.out.println("{\"test\":\"compiled_legacy_retrieval_counter\",\"passed\":true,\"random_observations\":"+checked+",\"partial_regression_reset\":true,\"completed_latch\":true,\"two_player_sum\":true,\"repeated_detection_no_accumulation\":true,\"actual_mixin_in_game_verified\":false}");
    }
}

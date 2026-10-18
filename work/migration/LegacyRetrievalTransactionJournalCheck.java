package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.*;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.MARKER_KEY;

/** Real ledger/player-fixture files and killed JVMs; no actual Player or world. */
public final class LegacyRetrievalTransactionJournalCheck {
    static int assertions;
    static final UUID ACTOR=new UUID(1,2),OFFLINE=new UUID(3,4),QUEST=new UUID(0,2);
    static final TaskKey KEY=new TaskKey(QUEST,1,"bq_standard:retrieval");
    static final Catalog CATALOG=new Catalog(List.of(QUEST),List.of(new Definition(KEY,1)));
    static final Binding BINDING=new Binding("a".repeat(64),"b".repeat(64));
    static final SourceDocument ORIGINAL=new SourceDocument("opaque-original-progress",new byte[]{0,-1,13,10});
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    interface Action{void run()throws Exception;}
    static void rejects(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static State state(boolean after){return new State(Map.of(),Map.of(KEY,new LegacyRetrievalEngine.State(Map.of(ACTOR,List.of(after?2:0),OFFLINE,List.of(7)),after?Set.of(ACTOR):Set.of())));}
    static CompoundTag player(int count){
        CompoundTag tag=new CompoundTag();tag.putUUID("UUID",ACTOR);ListTag inventory=new ListTag();CompoundTag item=new CompoundTag();
        item.putByte("Slot",(byte)0);item.putString("id","minecraft:oak_log");item.putInt("count",count);inventory.add(item);tag.put("Inventory",inventory);
        tag.putString("UnrelatedField","must survive");tag.putLongArray("Opaque",new long[]{Long.MIN_VALUE,Long.MAX_VALUE});
        CompoundTag data=new CompoundTag();CompoundTag other=new CompoundTag();other.putByteArray("payload",new byte[]{9,8,7});data.put("othermod:data",other);tag.put("NeoForgeData",data);return tag;
    }
    static CompoundTag after(CompoundTag before,int count){CompoundTag result=before.copy();if(count==0)result.getList("Inventory",10).clear();else result.getList("Inventory",10).getCompound(0).putInt("count",count);return result;}
    static int count(CompoundTag player){return player.getList("Inventory",10).getCompound(0).getInt("count");}
    static LegacyRetrievalTransactionJournal journal(LegacyQuestProgressFileStore store){return new LegacyRetrievalTransactionJournal(store,BINDING,CATALOG);}
    static void seed(Path base)throws Exception{
        Files.createDirectories(base);writePlayer(base,player(5),null);
        try(var store=LegacyQuestProgressFileStore.open(base.resolve("ledger"),BINDING,CATALOG)){store.commit(Optional.empty(),state(false),List.of(ORIGINAL));}
    }
    static void writePlayer(Path base,CompoundTag tag,String crash)throws Exception{
        Path temporary=base.resolve("player.pending"),target=base.resolve("player.dat");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,bytes);
        try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        halt(crash,"PLAYER_TEMP");Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);halt(crash,"PLAYER_PUBLISHED");
    }
    static CompoundTag readPlayer(Path base)throws Exception{return NbtIo.readCompressed(base.resolve("player.dat"),NbtAccounter.create(32L*1024*1024));}
    static void halt(String requested,String boundary){if(boundary.equals(requested))Runtime.getRuntime().halt(72);}
    static void successful(Path base)throws Exception{
        seed(base);CompoundTag before=readPlayer(base),after=after(before,3);
        Pending pending;
        try(var store=LegacyQuestProgressFileStore.open(base.resolve("ledger"),BINDING,CATALOG)){
            var journal=journal(store);pending=journal.prepare(ACTOR,before,after,state(true));
            check(store.current().orElseThrow().frame().snapshot().state().equals(state(false)),"Preparation changed progress before player save");
            check(readPlayer(base).equals(before),"Journal modified player file");
            rejects(()->journal.prepare(ACTOR,before,after,state(true)),"second pending transaction");
            rejects(()->journal.verifyResolvedPlayer(before),"player admitted with pending transaction");
            CompoundTag stamped=pending.playerDataToPersist();check(stamped.getCompound("NeoForgeData").getCompound("othermod:data").equals(before.getCompound("NeoForgeData").getCompound("othermod:data")),"Other persistent data lost");
            check(count(stamped)==3 && stamped.getString("UnrelatedField").equals("must survive"),"Player proposal changed unrelated fields");
            check(Arrays.equals(stamped.getLongArray("Opaque"),before.getLongArray("Opaque")),"Opaque player array changed");
            stamped.getCompound("NeoForgeData").remove(MARKER_KEY);check(LegacyRetrievalTransactionJournal.readMarker(pending.playerDataToPersist()).isPresent(),"Pending accessor aliases marker");
            pending.originalPlayerData().putInt("mutated",1);check(!pending.originalPlayerData().contains("mutated"),"Original player snapshot aliases caller");
            writePlayer(base,pending.playerDataToPersist(),null);
        }
        try(var store=LegacyQuestProgressFileStore.open(base.resolve("ledger"),BINDING,CATALOG)){
            var journal=journal(store);check(journal.pending().orElseThrow().transaction().equals(pending.transaction()),"Preparation did not survive restart");
            var resolution=journal.resolve(readPlayer(base));check(resolution.decision()==Decision.COMMIT,"Saved actor mutation did not commit progress");
            check(store.current().orElseThrow().frame().snapshot().state().equals(state(true)),"Progress not applied exactly");
            check(store.current().orElseThrow().frame().snapshot().sources().getFirst().equals(ORIGINAL),"Original bytes were not retained");
            check(journal.pending().isEmpty(),"Resolution still pending");journal.verifyResolvedPlayer(readPlayer(base));
            long revision=store.current().orElseThrow().frame().revision();rejects(()->journal.resolve(readPlayer(base)),"duplicate resolution");
            check(store.current().orElseThrow().frame().revision()==revision && count(readPlayer(base))==3,"Duplicate resolution changed files");
            rejects(()->journal.verifyResolvedPlayer(before),"dat_old marker rollback");
            CompoundTag legitimate=readPlayer(base);legitimate.getList("Inventory",10).getCompound(0).putInt("count",1);journal.verifyResolvedPlayer(legitimate);
            check(true,"Post-commit normal inventory changes must be allowed");
            Pending next=journal.prepare(ACTOR,legitimate,after(legitimate,0),state(true));
            check(journal.resolve(legitimate).decision()==Decision.ABORT,"Unsaved next mutation not aborted");journal.verifyResolvedPlayer(legitimate);
            check(!next.marker().transaction().equals(pending.marker().transaction()),"Transaction identity reused");
        }
    }
    static void rejection(Path base)throws Exception{
        seed(base);CompoundTag before=readPlayer(base),after=after(before,3);
        try(var store=LegacyQuestProgressFileStore.open(base.resolve("ledger"),BINDING,CATALOG)){
            var journal=journal(store);CompoundTag wrong=before.copy();wrong.putInt("UnrelatedField",9);
            rejects(()->journal.prepare(ACTOR,before,wrong,state(true)),"noninventory edit");
            CompoundTag bad=before.copy();bad.putString("Inventory","malformed");rejects(()->journal.prepare(ACTOR,bad,after,state(true)),"invalid inventory type");
            Pending pending=journal.prepare(ACTOR,before,after,state(true));String prepared=store.current().orElseThrow().sha256();
            CompoundTag noMarker=after.copy();rejects(()->journal.resolve(noMarker),"after inventory without marker");
            CompoundTag wrongCount=pending.playerDataToPersist();wrongCount.getList("Inventory",10).getCompound(0).putInt("count",4);rejects(()->journal.resolve(wrongCount),"marker with unproved inventory");
            CompoundTag wrongActor=pending.playerDataToPersist();wrongActor.putUUID("UUID",OFFLINE);rejects(()->journal.resolve(wrongActor),"foreign actor");
            CompoundTag future=pending.playerDataToPersist();future.getCompound("NeoForgeData").getCompound(MARKER_KEY).putInt("schema_version",9);rejects(()->journal.resolve(future),"future marker schema");
            CompoundTag extra=pending.playerDataToPersist();extra.getCompound("NeoForgeData").getCompound(MARKER_KEY).putInt("unknown",9);rejects(()->journal.resolve(extra),"unknown marker field");
            check(store.current().orElseThrow().sha256().equals(prepared),"Rejected recovery changed ledger");
            check(journal.resolve(before).decision()==Decision.ABORT,"Unpersisted inventory did not abort");
            check(store.current().orElseThrow().frame().snapshot().state().equals(state(false)),"Abort changed original progress");
        }
        Path bypass=base.resolve("bypass");seed(bypass);
        try(var store=LegacyQuestProgressFileStore.open(bypass.resolve("ledger"),BINDING,CATALOG)){
            var journal=journal(store);journal.prepare(ACTOR,player(5),player(3),state(true));
            var head=store.current().orElseThrow();store.commit(Optional.of(head.sha256()),state(true),head.frame().snapshot().sources());
            rejects(journal::pending,"outside writer advanced progress before player persistence");
        }
        Path unknown=base.resolve("unknown");seed(unknown);
        try(var store=LegacyQuestProgressFileStore.open(unknown.resolve("ledger"),BINDING,CATALOG)){
            var head=store.current().orElseThrow();var sources=new ArrayList<>(head.frame().snapshot().sources());
            CompoundTag record=new CompoundTag();record.putInt("schema_version",88);ByteArrayOutputStream out=new ByteArrayOutputStream();NbtIo.write(record,new DataOutputStream(out));
            sources.add(new SourceDocument("gtnh-generated-transaction:v1/unknown",out.toByteArray()));
            var next=store.commit(Optional.of(head.sha256()),state(false),sources);rejects(()->journal(store).pending(),"unknown journal schema");
            check(store.current().orElseThrow().sha256().equals(next.sha256()),"Unknown journal erased history");
        }
        Path concurrent=base.resolve("concurrent");seed(concurrent);
        try(var store=LegacyQuestProgressFileStore.open(concurrent.resolve("ledger"),BINDING,CATALOG)){
            var one=journal(store);var two=journal(store);CyclicBarrier start=new CyclicBarrier(2);AtomicInteger success=new AtomicInteger(),blocked=new AtomicInteger();
            try(ExecutorService threads=Executors.newFixedThreadPool(2)){
                List<Future<?>> results=new ArrayList<>();
                for(var facade:List.of(one,two))results.add(threads.submit(()->{try{start.await();facade.prepare(ACTOR,player(5),player(3),state(true));success.incrementAndGet();}catch(IOException expected){blocked.incrementAndGet();}catch(Exception failure){throw new RuntimeException(failure);}}));
                for(var result:results)result.get(20,TimeUnit.SECONDS);
            }
            check(success.get()==1 && blocked.get()==1,"Concurrent facades interleaved two preparations");
            check(store.current().orElseThrow().frame().revision()==2,"Concurrent prepare appended extra revisions");
            check(one.resolve(player(5)).decision()==Decision.ABORT,"Concurrent preparation could not resolve");
        }
    }
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[1]);String mode=args[0];
        if(mode.equals("seed")){seed(base);return;}
        if(mode.equals("crash")){
            String boundary=args[2];AtomicBoolean resolving=new AtomicBoolean();
            try(var store=new LegacyQuestProgressFileStore(base.resolve("ledger"),BINDING,CATALOG,point->{
                if(!resolving.get()&&point==LegacyQuestProgressFileStore.Boundary.TEMP_OPENED)halt(boundary,"PREPARE_TEMP");
                if(resolving.get()&&point==LegacyQuestProgressFileStore.Boundary.TEMP_OPENED)halt(boundary,"RESOLVE_TEMP");
                if(resolving.get()&&point==LegacyQuestProgressFileStore.Boundary.PUBLISHED)halt(boundary,"RESOLVE_PUBLISHED");
            })){
                var journal=journal(store);CompoundTag before=readPlayer(base);Pending pending=journal.prepare(ACTOR,before,after(before,3),state(true));halt(boundary,"PREPARED");
                writePlayer(base,pending.playerDataToPersist(),boundary);resolving.set(true);journal.resolve(readPlayer(base));halt(boundary,"RESOLVED");
            }
            throw new AssertionError("Crash boundary not reached");
        }
        if(mode.equals("recover")){
            boolean committed=Boolean.parseBoolean(args[2]);
            try(var store=LegacyQuestProgressFileStore.open(base.resolve("ledger"),BINDING,CATALOG)){
                var journal=journal(store);CompoundTag player=readPlayer(base);
                if(journal.pending().isPresent())check(journal.resolve(player).decision()==(committed?Decision.COMMIT:Decision.ABORT),"Wrong recovery decision");
                journal.verifyResolvedPlayer(player);
                check(store.current().orElseThrow().frame().snapshot().state().equals(state(committed)),"Recovered wrong progress");
                check(count(player)==(committed?3:5),"Recovery changed or misread inventory");
                long revision=store.current().orElseThrow().frame().revision();check(journal.pending().isEmpty(),"Recovery incomplete");
                journal.verifyResolvedPlayer(readPlayer(base));check(store.current().orElseThrow().frame().revision()==revision,"Recovery replay changed revision");
            }
            System.out.println("RECOVERED_VERIFIED");return;
        }
        successful(base.resolve("success"));rejection(base.resolve("rejected"));
        System.out.println("{\"test\":\"durable_inventory_progress_journal\",\"passed\":true,\"assertions\":"+assertions+",\"actual_player_save_fixtures\":0,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false,\"native_player_save_verified\":false,\"synthetic_player_nbt_used\":true}");
    }
}

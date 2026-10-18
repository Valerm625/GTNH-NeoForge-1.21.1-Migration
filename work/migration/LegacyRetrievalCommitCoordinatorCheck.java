package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;
import org.gtnhmigration.compat.LegacyRetrievalInventoryBridge.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.*;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.*;

/** Real Inventory/ItemStack/NBT/files, injectable save boundary; no live Player. */
public final class LegacyRetrievalCommitCoordinatorCheck {
    static int assertions;
    static HolderLookup.Provider registries;
    static final UUID ACTOR=new UUID(0,1),QUEST=new UUID(0,2);
    static final TaskKey KEY=new TaskKey(QUEST,0,"bq_standard:retrieval");
    static final Catalog CATALOG=new Catalog(List.of(QUEST),List.of(new Definition(KEY,1)));
    static final Binding BINDING=new Binding("a".repeat(64),"b".repeat(64));
    interface Action {void run()throws Exception;}
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void rejects(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static void recovery(Action action,String message)throws Exception{try{action.run();}catch(LegacyRetrievalCommitCoordinator.RecoveryRequiredException expected){assertions++;return;}throw new AssertionError("Did not isolate "+message);}
    static Inventory inventory(int count){Inventory i=new Inventory(null);ItemStack logs=new ItemStack(Items.OAK_LOG,count);logs.set(DataComponents.CUSTOM_NAME,Component.literal("keep original components"));i.items.set(0,logs);i.offhand.set(0,new ItemStack(Items.DIAMOND,7));return i;}
    static ListTag entries(Inventory i){ListTag out=new ListTag();for(int s=0;s<41;s++){ItemStack stack=s<36?i.items.get(s):s<40?i.armor.get(s-36):i.offhand.get(0);if(!stack.isEmpty()){CompoundTag entry=new CompoundTag();entry.putByte("Slot",(byte)(s<36?s:s<40?s+64:150));out.add(stack.save(registries,entry));}}return out;}
    static Prepared prepared(Inventory i,LegacyQuestProgressEnvelope.State state){
        var config=new Config<FrozenStack>(List.of(new Requirement<>(2,k->k.stack().is(Items.OAK_LOG))),true,false,false,false);
        Quest<FrozenStack> q=new Quest<>(QUEST,List.of(Task.retrieval(0,config)),"AND",-1,false,false,false,false);
        return LegacyRetrievalInventoryBridge.prepare(q,Map.of(0,state.tasks().getOrDefault(KEY,LegacyRetrievalEngine.State.empty())),ACTOR,List.of(ACTOR),Map.of(ACTOR,i),registries,new Gate(true,true,true,false));
    }
    static LegacyQuestProgressEnvelope.State state(LegacyQuestProgressFileStore s)throws IOException{return s.current().map(h->h.frame().snapshot().state()).orElse(LegacyQuestProgressEnvelope.State.empty());}
    enum Mode {NORMAL,SILENT,THROW_BEFORE,THROW_AFTER,CORRUPT,MEMORY_AFTER,MARKER_ONLY,REPLACE_STACK,DETACHED_CHANGE,BASELINE_SILENT,BASELINE_THROW,BASELINE_THROW_AFTER,MARKER_THROW}
    static final class Port implements LegacyRetrievalCommitCoordinator.PlayerPort {
        final Inventory inventory;final ItemStack original;final Path directory;final CompoundTag persistent=new CompoundTag();
        Mode mode=Mode.NORMAL;int saves,markers,barriers;byte[] baselineBytes;CompoundTag baseline;Runnable onSave=()->{};boolean exclusive=true;
        Port(Path directory,int count)throws IOException{this.directory=directory;Files.createDirectories(directory);inventory=LegacyRetrievalCommitCoordinatorCheck.inventory(count);original=inventory.items.get(0);persistent.putString("other:mod","preserved");CompoundTag nested=new CompoundTag();nested.putInt("other:nested",73);persistent.put(PERSISTED_KEY,nested);}
        public UUID actor(){return ACTOR;}public Inventory inventory(){return inventory;}public Path directory(){return directory;}
        public void assertExclusive(){barriers++;if(!exclusive)throw new IllegalStateException("Fixture barrier is not held");}
        public CompoundTag capture(){assertExclusive();CompoundTag p=new CompoundTag();p.putUUID("UUID",ACTOR);p.put("Inventory",entries(inventory));p.put("NeoForgeData",persistent.copy());p.putLongArray("opaque",new long[]{Long.MIN_VALUE,Long.MAX_VALUE});return p;}
        public void replaceMarker(CompoundTag expected,CompoundTag target)throws IOException{assertExclusive();LegacyRetrievalCommitCoordinator.replacePersistentMarker(persistent,expected,target);markers++;if(mode==Mode.MARKER_THROW && markers==2)throw new IOException("after marker write");}
        Path file(){return directory.resolve(ACTOR+".dat");}
        void write(CompoundTag p)throws IOException{Path temporary=directory.resolve("fixture.tmp");NbtIo.writeCompressed(p,temporary);Files.move(temporary,file(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        public void save()throws IOException{
            assertExclusive();saves++;onSave.run();
            if(saves==1){if(mode==Mode.BASELINE_SILENT)return;if(mode==Mode.BASELINE_THROW)throw new IOException("baseline failed");write(capture());baseline=capture();baselineBytes=Files.readAllBytes(file());if(mode==Mode.BASELINE_THROW_AFTER)throw new IOException("baseline published then threw");return;}
            switch(mode){
                case SILENT -> {return;}
                case THROW_BEFORE -> throw new IOException("before publication");
                case CORRUPT -> {Files.write(file(),new byte[]{1,2,3});return;}
                case MARKER_ONLY -> {CompoundTag partial=capture();partial.put("Inventory",baseline.get("Inventory").copy());write(partial);return;}
                case REPLACE_STACK -> {inventory.items.set(0,inventory.items.get(0).copy());return;}
                case DETACHED_CHANGE -> {original.setCount(99);return;}
                default -> {write(capture());if(mode==Mode.THROW_AFTER)throw new IOException("after publication");if(mode==Mode.MEMORY_AFTER)inventory.items.get(0).setCount(37);}
            }
        }
    }
    static LegacyRetrievalCommitCoordinator coordinator(LegacyQuestProgressFileStore s){return new LegacyRetrievalCommitCoordinator(s,BINDING,CATALOG);}
    static LegacyRetrievalTransactionJournal journal(LegacyQuestProgressFileStore s){return new LegacyRetrievalTransactionJournal(s,BINDING,CATALOG);}
    static void success(Path root)throws Exception{
        Port p=new Port(root.resolve("players"),5);
        try(var s=LegacyQuestProgressFileStore.open(root.resolve("ledger"),BINDING,CATALOG)){
            s.commit(Optional.empty(),LegacyQuestProgressEnvelope.State.empty(),List.of(new SourceDocument("original",new byte[]{0,-1,7})));
            var c=coordinator(s);var outcome=c.submit(prepared(p.inventory,state(s)),s.current(),p,()->true);
            check(outcome.resolution().decision()==Decision.COMMIT && outcome.attemptFailure().isEmpty(),"Normal submission failed");
            check(p.saves==2 && p.inventory.items.get(0)==p.original && p.original.getCount()==3,"Normal count/write sequence");
            check(state(s).tasks().get(KEY).completed().contains(ACTOR),"Task completion not persisted");
            check(p.inventory.offhand.get(0).getCount()==7 && p.persistent.getString("other:mod").equals("preserved") && p.persistent.getCompound(PERSISTED_KEY).getInt("other:nested")==73,"Other player data changed");
            check(Arrays.equals(s.current().orElseThrow().frame().snapshot().sources().getFirst().bytes(),new byte[]{0,-1,7}),"Original source bytes changed");
            check(!c.recoveryRequired() && !LegacyRetrievalInventoryBridge.recoveryRequired(p.inventory),"Successful inventory quarantined");
            journal(s).verifyResolvedPlayer(LegacyPlayerDataFileReader.read(p.directory,ACTOR).data());
            // Replay after completion, including promotion of an old v1 marker.
            CompoundTag old=p.persistent.getCompound(PERSISTED_KEY).getCompound(MARKER_KEY).copy();old.putInt("schema_version",1);
            p.persistent.getCompound(PERSISTED_KEY).remove(MARKER_KEY);p.persistent.put(MARKER_KEY,old);p.write(p.capture());
            p.saves=0;p.markers=0;
            outcome=c.submit(prepared(p.inventory,state(s)),s.current(),p,()->true);
            check(outcome.resolution().decision()==Decision.COMMIT && p.original.getCount()==3,"Replay double-consumed");
            check(!p.baseline.getCompound("NeoForgeData").contains(MARKER_KEY) && p.baseline.getCompound("NeoForgeData").getCompound(PERSISTED_KEY).getCompound(MARKER_KEY).getInt("schema_version")==2,"Legacy marker was not saved before intent");
            check(p.persistent.getCompound(PERSISTED_KEY).getInt("other:nested")==73,"Marker promotion removed another mod field");
        }
    }
    static void saveOutcomes(Path root)throws Exception{
        for(Mode mode:List.of(Mode.SILENT,Mode.THROW_BEFORE,Mode.THROW_AFTER,Mode.MARKER_THROW,Mode.BASELINE_THROW_AFTER)) for(int count:new int[]{2,5}){
            Path dir=root.resolve(mode+"-"+count);Port p=new Port(dir.resolve("players"),count);p.mode=mode;
            try(var s=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                var c=coordinator(s);var result=c.submit(prepared(p.inventory,state(s)),s.current(),p,()->true);
                boolean commit=mode==Mode.THROW_AFTER || mode==Mode.BASELINE_THROW_AFTER;
                check(result.resolution().decision()==(commit?Decision.COMMIT:Decision.ABORT),"Save outcome guessed from return: "+mode);
                check(p.inventory.items.get(0).getCount()==count-(commit?2:0),"Wrong restored count: "+mode);
                check(commit || p.inventory.items.get(0)==p.original,"Abort lost original object: "+mode);
                check(commit==state(s).tasks().containsKey(KEY),"Wrong progress after "+mode);
                check(journal(s).pending().isEmpty() && !c.recoveryRequired(),"Recoverable result left pending");
                check(result.attemptFailure().isPresent()==(mode!=Mode.SILENT),"Thrown diagnostic was lost");
                if(!commit)check(Arrays.equals(p.baselineBytes,Files.readAllBytes(p.file())),"Abort rewrote the player file");
                journal(s).verifyResolvedPlayer(p.capture());
            }
        }
    }
    static void uncertainty(Path root)throws Exception{
        for(Mode mode:List.of(Mode.CORRUPT,Mode.MEMORY_AFTER,Mode.MARKER_ONLY,Mode.REPLACE_STACK,Mode.DETACHED_CHANGE,Mode.BASELINE_SILENT,Mode.BASELINE_THROW)){
            Path dir=root.resolve(mode.toString());Port p=new Port(dir.resolve("players"),mode==Mode.DETACHED_CHANGE?2:5);p.mode=mode;
            try(var s=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                var c=coordinator(s);recovery(()->c.submit(prepared(p.inventory,state(s)),s.current(),p,()->true),mode.toString());
                check(c.recoveryRequired() && LegacyRetrievalInventoryBridge.recoveryRequired(p.inventory),"Uncertain state not quarantined");
                boolean baseline=mode==Mode.BASELINE_SILENT || mode==Mode.BASELINE_THROW;
                check(journal(s).pending().isPresent()!=baseline,"Lost pending intent");
                check(state(s).tasks().isEmpty(),"Uncertain save committed progress");
                int saves=p.saves;rejects(()->coordinator(s).submit(prepared(p.inventory,state(s)),s.current(),p,()->true),"retry quarantined ledger");check(p.saves==saves,"Retry wrote uncertain player");
                if(mode==Mode.REPLACE_STACK)check(p.inventory.items.get(0)!=p.original && p.inventory.items.get(0).getCount()==3,"Rollback overwrote externally replaced stack");
                if(mode==Mode.DETACHED_CHANGE)check(p.inventory.items.get(0).isEmpty() && p.original.getCount()==99,"Detached mutation was overwritten");
            }
            if(mode==Mode.MEMORY_AFTER){
                try(var s=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                    check(journal(s).resolveFromPlayerDirectory(p.directory).decision()==Decision.COMMIT,"Restart could not commit valid disk after-state");
                    check(state(s).tasks().get(KEY).completed().contains(ACTOR),"Restart recovered wrong progress");
                }
            }
        }
    }
    static void guards(Path root)throws Exception{
        Port p=new Port(root.resolve("players"),5);
        try(var s=LegacyQuestProgressFileStore.open(root.resolve("ledger"),BINDING,CATALOG)){
            var c=coordinator(s);rejects(()->c.submit(prepared(p.inventory,state(s)),s.current(),p,()->false),"unauthorized");
            check(p.saves==0 && s.current().isEmpty(),"Unauthorized call changed files");
            var old=s.current();s.commit(Optional.empty(),LegacyQuestProgressEnvelope.State.empty(),List.of());
            rejects(()->c.submit(prepared(p.inventory,state(s)),old,p,()->true),"stale head");check(p.saves==0,"Stale head called save");
            var current=s.current().orElseThrow();
            var forged=new LegacyQuestProgressFileStore.Head(current.sha256(),new Frame(9,current.frame().parentSha256(),current.frame().snapshot()));
            rejects(()->c.submit(prepared(p.inventory,state(s)),Optional.of(forged),p,()->true),"forged planning frame with real SHA");check(p.saves==0,"Inconsistent head called save");
            rejects(()->c.submit(prepared(inventory(5),state(s)),s.current(),p,()->true),"different actor inventory");check(p.saves==0,"Mismatched inventory called save");
            p.exclusive=false;rejects(()->c.submit(prepared(p.inventory,state(s)),s.current(),p,()->true),"missing barrier");p.exclusive=true;
            var plan=prepared(p.inventory,state(s));var head=s.current();
            p.onSave=()->{try{rejects(()->coordinator(s).submit(plan,head,p,()->true),"save callback reentry");}catch(Exception e){throw new RuntimeException(e);}};
            AtomicInteger calls=new AtomicInteger();var result=c.submit(plan,head,p,()->calls.incrementAndGet()!=5);
            check(result.resolution().decision()==Decision.ABORT && result.attemptFailure().isPresent(),"Authorization loss did not abort");
            check(p.original.getCount()==5 && p.inventory.items.get(0)==p.original && !c.recoveryRequired(),"Authorization rollback failed");
            check(p.saves==1 && journal(s).pending().isEmpty(),"Unauthorized delta was saved");
            rejects(()->c.submit(plan,s.current(),p,()->true),"reusing consumed preparation");
            check(p.saves==1 && !c.recoveryRequired() && journal(s).pending().isEmpty(),"Reused preparation entered persistence flow");
        }
    }
    static void ledgerFailures(Path root)throws Exception{
        for(int revision:new int[]{1,2}) for(var boundary:List.of(LegacyQuestProgressFileStore.Boundary.TEMP_FORCED,LegacyQuestProgressFileStore.Boundary.PUBLISHED)){
            Path dir=root.resolve(revision+"-"+boundary);Port p=new Port(dir.resolve("players"),5);AtomicInteger writes=new AtomicInteger();
            try(var s=new LegacyQuestProgressFileStore(dir.resolve("ledger"),BINDING,CATALOG,point->{
                if(point==LegacyQuestProgressFileStore.Boundary.TEMP_OPENED)writes.incrementAndGet();
                if(writes.get()==revision && point==boundary)throw new IOException("injected ledger boundary");
            })){
                var c=coordinator(s);recovery(()->c.submit(prepared(p.inventory,LegacyQuestProgressEnvelope.State.empty()),Optional.empty(),p,()->true),"ledger "+revision+" "+boundary);
                check(c.recoveryRequired(),"Uncertain ledger was reusable");
                check(p.original.getCount()==(revision==1?5:3),"Ledger failure refunded or double-consumed live items");
                check(p.saves==(revision==1?1:2),"Ledger failure retried player save");
            }
            byte[] disk=Files.readAllBytes(p.file());
            try(var s=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                var j=journal(s);
                if(j.pending().isPresent())check(j.resolveFromPlayerDirectory(p.directory).decision()==(revision==1?Decision.ABORT:Decision.COMMIT),"Wrong reopened ledger decision");
                else check(revision==1?s.current().isEmpty():state(s).tasks().get(KEY).completed().contains(ACTOR),"Wrong published ledger state");
                check(state(s).tasks().containsKey(KEY)==(revision==2),"Wrong progress after ledger reopen");
                j.verifyResolvedPlayer(LegacyPlayerDataFileReader.read(p.directory,ACTOR).data());
                check(Arrays.equals(disk,Files.readAllBytes(p.file())),"Ledger recovery rewrote player");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Path root=Path.of(args[0]);success(root.resolve("success"));saveOutcomes(root.resolve("save"));uncertainty(root.resolve("uncertain"));guards(root.resolve("guards"));ledgerFailures(root.resolve("ledger-failures"));
        System.out.println("{\"test\":\"retrieval_commit_coordinator\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_adapter_compiled\":true,\"native_player_save_verified\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0}");
    }
}

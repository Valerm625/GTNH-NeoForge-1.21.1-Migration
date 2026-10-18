package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.*;
import static org.gtnhmigration.compat.LegacyRetrievalCommitCoordinatorCheck.*;

/** Native save ordering simulated at cache/mirror/world boundaries. No game Player. */
public final class LegacyIntegratedOwnerCoordinatorCheck {
    static int assertions;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void recovery(Action action,String message)throws Exception{
        try{action.run();}catch(LegacyRetrievalCommitCoordinator.RecoveryRequiredException expected){assertions++;return;}
        throw new AssertionError("Did not preserve pending recovery: "+message);
    }
    enum Mode {NORMAL,EARLY_THROW,CACHE_ONLY,MIRROR_ONLY,THROW_AFTER_WORLD,
        MIRROR_SILENT_WORLD_SAVED,CACHE_DIVERGED,MIRROR_DIVERGED,REPAIR_SILENT,
        REPAIR_THROW_AFTER,REPAIR_WORLD_CHANGED,MEMORY_AFTER_WORLD,BASELINE_WORLD_SILENT,
        BASELINE_MIRROR_SILENT,BASELINE_THROW_AFTER,BASELINE_CACHE_WRONG}
    static final class OwnerPort extends LegacyRetrievalCommitCoordinator.WorldPlayerPort {
        final Port live;final Path world;final CompoundTag metadata=new CompoundTag();
        CompoundTag cache;Mode mode;int saves,repairs;byte[] baselineWorld;Runnable onSave=()->{};
        OwnerPort(Path root,int count,Mode mode)throws IOException{
            world=root.resolve("world");Files.createDirectories(world);live=new Port(world.resolve("playerdata"),count);this.mode=mode;
            metadata.putString("LevelName","fixture world metadata");metadata.putLongArray("opaque",new long[]{Long.MIN_VALUE,Long.MAX_VALUE});
        }
        public UUID actor(){return ACTOR;}public Inventory inventory(){return live.inventory;}public Path directory(){return world;}
        Path playerDirectory(){return live.directory;}public void assertExclusive(){live.assertExclusive();}
        public CompoundTag capture(){return live.capture();}
        public void replaceMarker(CompoundTag before,CompoundTag after)throws IOException{live.replaceMarker(before,after);}
        CompoundTag cachedPlayer()throws IOException{if(cache==null)throw new IOException("missing cache");return cache.copy();}
        void worldWrite(CompoundTag player)throws IOException{
            CompoundTag data=metadata.copy();data.put("Player",player.copy());CompoundTag root=new CompoundTag();root.put("Data",data);root.putString("root:opaque","kept");
            Path temporary=world.resolve("world.tmp");NbtIo.writeCompressed(root,temporary);
            Files.move(temporary,world.resolve("level.dat"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
        public void save()throws IOException{
            assertExclusive();saves++;onSave.run();
            if(saves>1 && mode==Mode.EARLY_THROW)throw new IOException("before cache capture");
            cache=capture();
            if(saves>1 && mode==Mode.CACHE_DIVERGED){cache.putUUID("UUID",new UUID(0,99));return;}
            if(saves>1 && mode==Mode.CACHE_ONLY)return;
            if(!(saves==1 && mode==Mode.BASELINE_MIRROR_SILENT) && !(saves>1 && mode==Mode.MIRROR_SILENT_WORLD_SAVED))live.write(cache);
            if(saves>1){
                if(mode==Mode.MIRROR_DIVERGED){CompoundTag bad=cache.copy();bad.putUUID("UUID",new UUID(0,99));live.write(bad);return;}
                if(List.of(Mode.MIRROR_ONLY,Mode.REPAIR_SILENT,Mode.REPAIR_THROW_AFTER,Mode.REPAIR_WORLD_CHANGED).contains(mode))return;
            }
            if(saves==1 && mode==Mode.BASELINE_WORLD_SILENT)return;
            worldWrite(cache);
            if(saves==1){baselineWorld=Files.readAllBytes(world.resolve("level.dat"));if(mode==Mode.BASELINE_CACHE_WRONG)cache.putUUID("UUID",new UUID(0,99));}
            if(saves>1 && mode==Mode.MEMORY_AFTER_WORLD)live.inventory.items.get(0).setCount(41);
            if(saves>1 && mode==Mode.THROW_AFTER_WORLD || saves==1 && mode==Mode.BASELINE_THROW_AFTER)throw new IOException("after world publication");
        }
        void savePlayers()throws IOException{
            assertExclusive();repairs++;if(mode==Mode.REPAIR_SILENT)return;
            cache=capture();live.write(cache);
            if(mode==Mode.REPAIR_WORLD_CHANGED){metadata.putInt("external-change",8);worldWrite(cache);}
            if(mode==Mode.REPAIR_THROW_AFTER)throw new IOException("after repaired cache and mirror");
        }
    }
    static void outcomes(Path root)throws Exception{
        for(Mode mode:List.of(Mode.NORMAL,Mode.EARLY_THROW,Mode.CACHE_ONLY,Mode.MIRROR_ONLY,Mode.THROW_AFTER_WORLD,Mode.REPAIR_THROW_AFTER,Mode.BASELINE_THROW_AFTER))for(int count:new int[]{2,5}){
            Path dir=root.resolve(mode+"-"+count);OwnerPort p=new OwnerPort(dir,count,mode);
            check(!Files.exists(p.world.resolve("level.dat")),"Fixture was not a first world-player save");
            try(var store=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                var c=coordinator(store);var outcome=c.submit(prepared(p.live.inventory,state(store)),store.current(),p,()->true);
                boolean commit=List.of(Mode.NORMAL,Mode.THROW_AFTER_WORLD,Mode.BASELINE_THROW_AFTER).contains(mode);
                check(outcome.resolution().decision()==(commit?Decision.COMMIT:Decision.ABORT),"Wrong world decision: "+mode);
                check(p.live.inventory.items.get(0).getCount()==count-(commit?2:0),"Wrong inventory count: "+mode);
                if(!commit)check(p.live.inventory.items.get(0)==p.live.original,"Abort lost original ItemStack");
                check(state(store).tasks().containsKey(KEY)==commit,"World progress mismatched outcome");
                check(journal(store).pending().isEmpty() && !c.recoveryRequired(),"Known outcome left quarantined");
                check(p.repairs==(List.of(Mode.CACHE_ONLY,Mode.MIRROR_ONLY,Mode.REPAIR_THROW_AFTER).contains(mode)?1:0),"Wrong secondary save count");
                var world=LegacyPlayerDataFileReader.readWorldPlayer(p.world,ACTOR);
                p.verifySecondary(world.data());check(LegacyRetrievalCommitCoordinator.sameCritical(world.data(),p.capture()),"World/live mismatch");
                check(world.documentData().getString("root:opaque").equals("kept") && world.documentData().getCompound("Data").getString("LevelName").equals("fixture world metadata"),"Opaque world metadata lost");
                check(p.live.persistent.getString("other:mod").equals("preserved") && p.live.inventory.offhand.get(0).getCount()==7,"Other mod data/offhand lost");
                check(outcome.attemptFailure().isPresent()==List.of(Mode.EARLY_THROW,Mode.THROW_AFTER_WORLD,Mode.REPAIR_THROW_AFTER,Mode.BASELINE_THROW_AFTER).contains(mode),"Save diagnostic lost");
                if(!commit){
                    check(Arrays.equals(p.baselineWorld,Files.readAllBytes(p.world.resolve("level.dat"))),"Abort rewrote world file");
                    byte[] authorityBytes=Files.readAllBytes(p.world.resolve("level.dat"));
                    // A later native-order cache/mirror/world save must not reinstate the discarded debit.
                    p.mode=Mode.NORMAL;p.save();
                    check(LegacyRetrievalCommitCoordinator.sameCritical(world.data(),LegacyPlayerDataFileReader.readWorldPlayer(p.world,ACTOR).data()),"Later save resurrected canceled debit");
                    check(Arrays.equals(authorityBytes,Files.readAllBytes(p.world.resolve("level.dat"))),"Later equivalent fixture save changed world bytes");
                }
            }
            try(var reopened=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                journal(reopened).verifyResolvedPlayer(LegacyPlayerDataFileReader.readWorldPlayer(p.world,ACTOR).data());
                check(journal(reopened).pending().isEmpty(),"Resolved owner transaction reopened pending");
            }
        }
    }
    static void unknowns(Path root)throws Exception{
        for(Mode mode:List.of(Mode.MIRROR_SILENT_WORLD_SAVED,Mode.CACHE_DIVERGED,Mode.MIRROR_DIVERGED,Mode.REPAIR_SILENT,Mode.REPAIR_WORLD_CHANGED,Mode.MEMORY_AFTER_WORLD,Mode.BASELINE_WORLD_SILENT,Mode.BASELINE_MIRROR_SILENT,Mode.BASELINE_CACHE_WRONG)){
            Path dir=root.resolve(mode.toString());OwnerPort p=new OwnerPort(dir,5,mode);
            try(var store=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                var c=coordinator(store);recovery(()->c.submit(prepared(p.live.inventory,state(store)),store.current(),p,()->true),mode.toString());
                check(c.recoveryRequired() && LegacyRetrievalInventoryBridge.recoveryRequired(p.live.inventory),"Unknown owner state not quarantined");
                boolean baseline=mode.toString().startsWith("BASELINE_");
                check(journal(store).pending().isPresent()!=baseline,"Intent missing or created before baseline validation");
                check(state(store).tasks().isEmpty(),"Unknown secondary state committed progress");
                if(!baseline)check(journal(store).pending().orElseThrow().authority()==Authority.WORLD_PLAYER,"Owner intent lost authority");
                if(mode==Mode.CACHE_DIVERGED)check(p.cache.getUUID("UUID").equals(new UUID(0,99)) && p.repairs==0,"Unknown cache overwritten");
                if(mode==Mode.MIRROR_DIVERGED)check(p.repairs==0,"Unknown mirror overwritten");
                if(mode==Mode.REPAIR_WORLD_CHANGED)check(LegacyPlayerDataFileReader.readWorldPlayer(p.world,ACTOR).documentData().getCompound("Data").getInt("external-change")==8,"World change overwritten");
                int saves=p.saves;
                rejects(()->coordinator(store).submit(prepared(p.live.inventory,state(store)),store.current(),p,()->true),"quarantined owner retry");
                check(p.saves==saves,"Unknown owner state triggered another save");
            }
            try(var reopened=LegacyQuestProgressFileStore.open(dir.resolve("ledger"),BINDING,CATALOG)){
                var pending=journal(reopened).pending();
                if(pending.isPresent()){
                    check(pending.get().authority()==Authority.WORLD_PLAYER,"Reopened pending owner changed source");
                    var before=reopened.current();
                    rejects(()->journal(reopened).resolveFromPlayerDirectory(p.playerDirectory()),"UUID mirror used for pending owner");
                    check(reopened.current().equals(before),"Wrong authority advanced ledger");
                }
            }
        }
    }
    static void guardsAndReplay(Path root)throws Exception{
        OwnerPort p=new OwnerPort(root,5,Mode.NORMAL);
        try(var store=LegacyQuestProgressFileStore.open(root.resolve("ledger"),BINDING,CATALOG)){
            var c=coordinator(store);
            rejects(()->c.submit(prepared(p.live.inventory,state(store)),store.current(),p,()->false),"unauthorized owner");check(p.saves==0,"Unauthorized owner saved world");
            var plan=prepared(p.live.inventory,state(store));var head=store.current();AtomicInteger checks=new AtomicInteger();
            p.onSave=()->{try{rejects(()->coordinator(store).submit(plan,head,p,()->true),"native save callback owner reentry");}catch(Exception e){throw new RuntimeException(e);}};
            var outcome=c.submit(plan,head,p,()->checks.incrementAndGet()!=5);
            check(outcome.resolution().decision()==Decision.ABORT && p.saves==1 && p.repairs==0,"Authority loss changed owner caches");
            check(p.live.original.getCount()==5 && !c.recoveryRequired(),"Owner authority loss failed rollback");
            p.onSave=()->{};p.saves=0;
            c.submit(prepared(p.live.inventory,state(store)),store.current(),p,()->true);
            check(p.live.original.getCount()==3,"First owner submit wrong debit");
            p.saves=0;c.submit(prepared(p.live.inventory,state(store)),store.current(),p,()->true);
            check(p.live.original.getCount()==3,"Completed owner task double-consumed");
            p.verifySecondary(LegacyPlayerDataFileReader.readWorldPlayer(p.world,ACTOR).data());
        }
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Path root=Path.of(args[0]);outcomes(root.resolve("outcomes"));unknowns(root.resolve("unknowns"));guardsAndReplay(root.resolve("guards"));
        System.out.println("{\"test\":\"integrated_owner_coordinator\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_owner_adapter_compiled\":true,\"native_player_save_verified\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0}");
    }
}

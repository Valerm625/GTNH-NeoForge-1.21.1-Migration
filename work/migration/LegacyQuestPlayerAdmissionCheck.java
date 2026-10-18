package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.State;
import org.gtnhmigration.compat.LegacyQuestProgressFileStore.Head;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.SourceDocument;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;
import static org.gtnhmigration.compat.LegacyQuestWorldStoreCheck.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

/** Actual NBT/files and production receipt/save-scope; native load is a fixture. */
public final class LegacyQuestPlayerAdmissionCheck {
    static int assertions;
    interface Action{void run()throws Exception;}
    static void yes(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void refuses(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalStateException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static void finish(LegacyQuestPlayerAdmission.Receipt receipt,Optional<CompoundTag> data)throws IOException{
        receipt.finish(data,ACTOR,data.orElseGet(()->player(5)).getCompound("NeoForgeData"));
    }
    static Path playerPath(Path world){return world.resolve("playerdata").resolve(ACTOR+".dat");}
    static CompoundTag commit(LegacyQuestWorldStore store,Authority authority)throws Exception{
        var pending=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,player(5),player(3),after(),authority);
        var saved=pending.playerDataToPersist();writePlayer(store.worldDirectory(),saved);writeWorld(store.worldDirectory(),saved);store.recoverOffline(()->{});return saved;
    }
    static void normal(Path root)throws Exception{
        for(boolean owner:List.of(false,true))for(boolean history:List.of(false,true)){
            Path dir=root.resolve(owner+"-"+history);
            try(var store=create(dir)){
                CompoundTag data=history?commit(store,owner?Authority.WORLD_PLAYER:Authority.PLAYER_FILE):player(5);
                writePlayer(dir,data);writeWorld(dir,data);var before=bytes(dir);var receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,owner,owner?data:null);
                yes(receipt.actor().equals(ACTOR) && receipt.authority().orElseThrow()==(owner?Authority.WORLD_PLAYER:Authority.PLAYER_FILE),"Native branch selection changed");
                CompoundTag loaded=owner?data.copy():receipt.readNativeFile(playerPath(dir));
                if(!owner){loaded.putInt("DataVersion",3955);loaded.putString("fixture:datafix","native stage modeled, not executed");}
                receipt.acceptPayload(ACTOR,loaded);finish(receipt,Optional.of(loaded));
                yes(bytes(dir).equals(before),"Admission wrote player/world/ledger");refuses(()->finish(receipt,Optional.of(loaded)),"receipt consumed twice");
                yes(journal(store).hasPlayerHistory(ACTOR)==history,"Player history predicate changed");
            }
        }
        Path fresh=root.resolve("fresh");try(var store=create(fresh)){
            Files.createDirectory(fresh.resolve("playerdata"));var receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,false,null);
            yes(receipt.authority().isEmpty(),"New player invented an authority");receipt.allowEmptyFallback();finish(receipt,Optional.empty());
            yes(!Files.exists(playerPath(fresh)),"First login wrote a player file");
        }
        Path fallback=root.resolve("owner-null");try(var store=create(fallback)){
            writePlayer(fallback,player(5));var receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,true,null);
            yes(receipt.authority().orElseThrow()==Authority.PLAYER_FILE,"Owner null tag no longer follows UUID file branch");var data=receipt.readNativeFile(playerPath(fallback));receipt.acceptPayload(ACTOR,data);finish(receipt,Optional.of(data));
        }
    }
    static void entryFailures(Path root)throws Exception{
        for(int scenario=0;scenario<9;scenario++){
            Path dir=root.resolve("case-"+scenario);try(var store=create(dir)){
                Files.createDirectories(dir.resolve("playerdata"));CompoundTag data=player(5);writePlayer(dir,data);writeWorld(dir,data);
                switch(scenario){
                    case 0->{journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,data,player(3),after());}
                    case 1->{Files.delete(playerPath(dir));Files.write(playerPath(dir).resolveSibling(ACTOR+".dat_old"),compressed(data));}
                    case 2->Files.write(playerPath(dir),new byte[]{1,2,3});
                    case 3->{commit(store,Authority.PLAYER_FILE);writePlayer(dir,data);}
                    case 4->{commit(store,Authority.PLAYER_FILE);Files.delete(playerPath(dir));}
                    case 5->{var p=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,data,player(3),after());writePlayer(dir,p.originalPlayerData());store.recoverOffline(()->{});Files.delete(playerPath(dir));}
                    case 6->{CompoundTag p=player(5);p.putUUID("UUID",OTHER);writePlayer(dir,p);}
                    case 8->{
                        var p=commit(store,Authority.PLAYER_FILE);var forge=p.getCompound("NeoForgeData");
                        var marker=forge.getCompound(LegacyRetrievalTransactionJournal.PERSISTED_KEY).getCompound(LegacyRetrievalTransactionJournal.MARKER_KEY).copy();
                        marker.putInt("schema_version",1);forge.getCompound(LegacyRetrievalTransactionJournal.PERSISTED_KEY).remove(LegacyRetrievalTransactionJournal.MARKER_KEY);forge.put(LegacyRetrievalTransactionJournal.MARKER_KEY,marker);writePlayer(dir,p);
                    }
                    case 7->{Files.write(dir.resolve("level.dat"),new byte[]{1,2});}
                }
                var before=bytes(dir);boolean owner=scenario==7;
                refuses(()->LegacyQuestPlayerAdmission.begin(store,ACTOR,owner,owner?data:null),"unsafe native entry "+scenario);
                yes(bytes(dir).equals(before),"Rejected entry changed data");
            }
        }
    }
    static void receipts(Path root)throws Exception{
        for(int scenario=0;scenario<13;scenario++){
            Path dir=root.resolve("receipt-"+scenario);try(var store=create(dir)){
                writePlayer(dir,player(5));var r=LegacyQuestPlayerAdmission.begin(store,ACTOR,false,null);int index=scenario;
                refuses(()->{
                    switch(index){
                        case 0->r.readNativeFile(playerPath(dir).resolveSibling(ACTOR+".dat_old"));
                        case 1->{r.readNativeFile(playerPath(dir));r.readNativeFile(playerPath(dir));}
                        case 2->r.acceptPayload(ACTOR,player(5));
                        case 3->{var data=r.readNativeFile(playerPath(dir));r.acceptPayload(OTHER,data);}
                        case 4->{var data=r.readNativeFile(playerPath(dir));data.putUUID("UUID",OTHER);r.acceptPayload(ACTOR,data);}
                        case 5->{var data=r.readNativeFile(playerPath(dir));r.acceptPayload(ACTOR,data);r.acceptPayload(ACTOR,data);}
                        case 6->finish(r,Optional.of(player(5)));
                        case 7->{var data=r.readNativeFile(playerPath(dir));r.acceptPayload(ACTOR,data);finish(r,Optional.empty());}
                        case 8->r.allowEmptyFallback();
                        case 9->{writePlayer(dir,player(99));r.readNativeFile(playerPath(dir));}
                        case 10->{var data=r.readNativeFile(playerPath(dir));r.acceptPayload(ACTOR,data);writePlayer(dir,player(99));finish(r,Optional.of(data));}
                        case 12->{var data=r.readNativeFile(playerPath(dir));r.acceptPayload(ACTOR,data);finish(r,Optional.of(data.copy()));}
                        case 11->{var data=r.readNativeFile(playerPath(dir));r.acceptPayload(ACTOR,data);var head=store.ledger().current().orElseThrow();var sources=new ArrayList<>(head.frame().snapshot().sources());sources.add(new SourceDocument("callback-write",new byte[]{7}));store.ledger().commit(Optional.of(head.sha256()),State.empty(),sources);finish(r,Optional.of(data));}
                    }
                },"bad receipt sequence "+scenario);
                writePlayer(dir,player(5));refuses(()->r.readNativeFile(playerPath(dir)),"reuse failed receipt "+scenario);
            }
        }
        Path live=root.resolve("live-marker");try(var store=create(live)){
            commit(store,Authority.PLAYER_FILE);var r=LegacyQuestPlayerAdmission.begin(store,ACTOR,false,null);var data=r.readNativeFile(playerPath(live));r.acceptPayload(ACTOR,data);
            refuses(()->r.finish(Optional.of(data),ACTOR,new CompoundTag()),"live persistent marker lost in loading event");
            var second=LegacyQuestPlayerAdmission.begin(store,ACTOR,false,null);var next=second.readNativeFile(playerPath(live));second.acceptPayload(ACTOR,next);
            refuses(()->second.finish(Optional.of(next),OTHER,next.getCompound("NeoForgeData")),"live UUID changed in loading event");
        }
        Path fresh=root.resolve("new-race");try(var store=create(fresh)){
            Files.createDirectory(fresh.resolve("playerdata"));var r=LegacyQuestPlayerAdmission.begin(store,ACTOR,false,null);writePlayer(fresh,player(5));refuses(()->finish(r,Optional.empty()),"file appeared during first login");
        }
        Path thread=root.resolve("thread");try(var store=create(thread)){
            writePlayer(thread,player(5));var r=LegacyQuestPlayerAdmission.begin(store,ACTOR,false,null);AtomicReference<Throwable> failure=new AtomicReference<>();Thread other=new Thread(()->{try{r.readNativeFile(playerPath(thread));}catch(Throwable e){failure.set(e);}});other.start();other.join(5000);
            yes(!other.isAlive() && failure.get() instanceof IOException,"Cross-thread receipt escaped");refuses(()->r.readNativeFile(playerPath(thread)),"cross-thread violation was not retained");
        }
    }
    static void ownerCache(Path root)throws Exception{
        Path dir=root.resolve("owner");try(var store=create(dir)){
            var p=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,player(5),player(3),after(),Authority.WORLD_PLAYER);
            writeWorld(dir,p.playerDataToPersist());writePlayer(dir,p.playerDataToPersist());String head=store.ledger().current().orElseThrow().sha256();
            refuses(()->LegacyQuestPlayerAdmission.verifyPendingWorldCache(store,p.originalPlayerData()),"stale loaded world cache before recovery");
            refuses(()->LegacyQuestPlayerAdmission.verifyPendingWorldCache(store,null),"missing loaded world cache before recovery");
            yes(store.ledger().current().orElseThrow().sha256().equals(head),"Cache rejection resolved journal");
            LegacyQuestPlayerAdmission.verifyPendingWorldCache(store,p.playerDataToPersist());store.recoverOffline(()->{});
            LegacyQuestPlayerAdmission.verifyResolvedWorldCache(store,p.playerDataToPersist());
            refuses(()->LegacyQuestPlayerAdmission.verifyResolvedWorldCache(store,p.originalPlayerData()),"resolved stale world cache");
            refuses(()->LegacyQuestPlayerAdmission.begin(store,ACTOR,true,p.originalPlayerData()),"owner admission used stale cache");
            var receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,true,p.playerDataToPersist());
            var fileReceipt=receipt;refuses(()->fileReceipt.readNativeFile(playerPath(dir)),"owner path switched to UUID mirror");
            receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,true,p.playerDataToPersist());var mutated=p.playerDataToPersist();mutated.putString("other:cache","changed after receipt");var captured=receipt;
            refuses(()->captured.acceptPayload(ACTOR,mutated),"owner payload changed after validation");
        }
    }
    static void permits(Path root)throws Exception{
        for(boolean owner:List.of(false,true)){
            Object server=new Object(),list=new Object(),file=new Object(),player=new Object();
            try(var lease=begin(List.of(server,list,file),List.of(root.resolve(UUID.randomUUID().toString())),owner)){
                LegacyRetrievalSaveScope.root(server,PLAYER_LOAD,player,()->{
                    enter(list,null,PLAYER_LOAD,player,Entry.ENTER);
                    if(owner)child(server,PLAYER_LOAD,PLAYER_NBT_LOAD,player,()->{enter(server,null,PLAYER_NBT_LOAD,player,Entry.ENTER);return null;});
                    else child(server,PLAYER_LOAD,PLAYER_FILE_LOAD,player,()->{enter(file,null,PLAYER_FILE_LOAD,player,Entry.ENTER);child(server,PLAYER_FILE_LOAD,PLAYER_NBT_LOAD,player,()->{enter(server,null,PLAYER_NBT_LOAD,player,Entry.ENTER);return null;});return null;});
                    return null;
                });lease.release();yes(!lease.recoveryRequired(),"Valid load permit chain poisoned");
            }
        }
        for(String attack:List.of("save","direct_payload","nested_list","other_player","other_child","swallowed_payload")){
            Object server=new Object(),list=new Object(),file=new Object(),player=new Object();
            try(var lease=begin(List.of(server,list,file),List.of(root.resolve(UUID.randomUUID().toString())),false)){
                refuses(()->LegacyRetrievalSaveScope.root(server,PLAYER_LOAD,player,()->{
                    enter(list,null,PLAYER_LOAD,player,Entry.ENTER);
                    switch(attack){
                        case "save"->enter(file,null,PLAYER_FILE,player,Entry.ENTER);
                        case "direct_payload"->enter(server,null,PLAYER_NBT_LOAD,player,Entry.ENTER);
                        case "nested_list"->enter(list,null,PLAYER_LOAD,player,Entry.ENTER);
                        case "other_child"->child(server,PLAYER_LOAD,PLAYER_NBT_LOAD,new Object(),()->null);
                        case "other_player"->child(server,PLAYER_LOAD,PLAYER_NBT_LOAD,player,()->{enter(server,null,PLAYER_NBT_LOAD,new Object(),Entry.ENTER);return null;});
                        default->{try{enter(server,null,PLAYER_NBT_LOAD,player,Entry.ENTER);}catch(Violation ignored){}}
                    }return null;
                }),"load callback "+attack);yes(lease.recoveryRequired(),"Load callback was not quarantined");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length>1){
            Path dir=Path.of(args[1]);try(var store=LegacyQuestWorldStore.open(dir)){
                var cached=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data();
                LegacyQuestPlayerAdmission.verifyPendingWorldCache(store,cached);store.recoverOffline(()->{});LegacyQuestPlayerAdmission.verifyResolvedWorldCache(store,cached);
                boolean owner=args[0].equals("WORLD_PLAYER");var receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,owner,cached);
                var data=owner?cached:receipt.readNativeFile(playerPath(dir));receipt.acceptPayload(ACTOR,data);finish(receipt,Optional.of(data));
                yes(journal(store).pending().isEmpty(),"Fresh-process admission left pending state");
            }System.out.println("FRESH_PROCESS_ADMISSION_OK");return;
        }
        Path root=Path.of(args[0]);normal(root.resolve("normal"));entryFailures(root.resolve("entry"));receipts(root.resolve("receipt"));ownerCache(root.resolve("cache"));permits(root.resolve("permits"));
        System.out.println("{\"test\":\"player_admission_receipts\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_call_sites_registered\":true,\"native_lifecycle_executed\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0}");
    }
}

package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.State;
import org.gtnhmigration.compat.LegacyQuestProgressFileStore.Head;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Pending;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Decision;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;
import static org.gtnhmigration.compat.LegacyQuestWorldStoreCheck.*;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.sameNbt;

/** Synthetic files/cache. Production recovery, main journal and admission run. */
public final class LegacyOwnerTransactionRecoveryCheck {
    static int assertions;
    interface Action{void run()throws Exception;}
    static void yes(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void no(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static Path playerFile(Path dir){return dir.resolve("playerdata").resolve(ACTOR+".dat");}
    static Path recovery(Path dir){return metadata(dir).resolve(LegacyOwnerTransactionRecovery.DIRECTORY);}
    static CompoundTag rich(int count){return LegacyPlayerMarkerUpgradeCheck.richPlayer(count);}
    static Pending prepare(LegacyQuestWorldStore store,boolean history)throws Exception{
        CompoundTag before=rich(5);
        if(history){var seed=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,rich(7),before,State.empty(),Authority.WORLD_PLAYER);writeWorld(store.worldDirectory(),seed.playerDataToPersist());writePlayer(store.worldDirectory(),seed.playerDataToPersist());store.recoverOffline(()->{});before=LegacyPlayerMarkerUpgradeCheck.legacy(seed.playerDataToPersist());}
        var after=before.copy();after.getList("Inventory",10).getCompound(0).putInt("count",3);return journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,before,after,after(),Authority.WORLD_PLAYER);
    }
    static CompoundTag chosen(Pending pending,Decision decision){return decision==Decision.COMMIT?pending.playerDataToPersist():pending.originalPlayerData();}
    static Decision other(Decision decision){return decision==Decision.COMMIT?Decision.ABORT:Decision.COMMIT;}
    static void field(DataOutputStream out,String name,Tag value)throws IOException{LegacyPlayerMarkerUpgradeCheck.field(out,name,value);}
    static void compound(DataOutputStream out,String name)throws IOException{LegacyPlayerMarkerUpgradeCheck.compound(out,name);}
    static void proof(DataOutputStream out)throws IOException{LegacyPlayerMarkerUpgradeCheck.proof(out);}
    /** Independent fixed-order expected NBT, with foreign negative zeros/NaNs.
     * Foreign mirror fields differ from world fields and must remain local. */
    static byte[] golden(CompoundTag player,boolean world)throws IOException{return golden(player,world,false);}
    static byte[] golden(CompoundTag player,boolean world,boolean appendLegacy)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);compound(out,world?"world root":"mirror root");
        if(world){proof(out);compound(out,"Data");field(out,"Time",LongTag.valueOf(987654321));compound(out,"Player");}
        field(out,"Inventory",player.get("Inventory"));proof(out);compound(out,"NeoForgeData");field(out,"local_mod",StringTag.valueOf(world?"world private":"mirror private"));
        CompoundTag forge=player.getCompound("NeoForgeData");Tag legacy=forge.get(LegacyRetrievalTransactionJournal.MARKER_KEY),modern=forge.getCompound("PlayerPersisted").get(LegacyRetrievalTransactionJournal.MARKER_KEY);
        if(legacy!=null && !appendLegacy)field(out,LegacyRetrievalTransactionJournal.MARKER_KEY,legacy);
        compound(out,"PlayerPersisted");proof(out);if(modern!=null)field(out,LegacyRetrievalTransactionJournal.MARKER_KEY,modern);out.writeByte(0);if(legacy!=null && appendLegacy)field(out,LegacyRetrievalTransactionJournal.MARKER_KEY,legacy);out.writeByte(0);
        field(out,"UUID",player.get("UUID"));field(out,"local_only",StringTag.valueOf(world?"source-world":"retain-mirror"));out.writeByte(0);
        if(world){field(out,"world_mod",StringTag.valueOf("unchanged"));out.writeByte(0);out.writeByte(0);}return bytes.toByteArray();
    }
    static byte[] gzip(byte[] raw)throws IOException{return LegacyPlayerMarkerUpgradeCheck.gzip(raw);}
    static byte[] gunzip(byte[] raw)throws IOException{return LegacyPlayerMarkerUpgradeCheck.gunzip(raw);}
    static void files(Path dir,Pending transaction,Decision world,Decision mirror)throws Exception{
        Files.createDirectories(dir.resolve("playerdata"));Files.write(dir.resolve("level.dat"),withHeader(gzip(golden(chosen(transaction,world),true))));Files.write(playerFile(dir),withHeader(gzip(golden(chosen(transaction,mirror),false))));
    }
    static void archived(Path dir,byte[] raw)throws IOException{Path blob=recovery(dir).resolve("blobs").resolve(LegacyQuestProgressStoreCodec.digest(raw)+".gz");yes(Files.isRegularFile(blob) && Arrays.equals(raw,Files.readAllBytes(blob)),"Recovery discarded original/target bytes");}
    static CompoundTag cache(Pending pending,Decision decision){var value=chosen(pending,decision);value.putString("loaded:extra","retain-cache");return value;}
    static void finalState(LegacyQuestWorldStore store,Pending transaction,Decision decision,CompoundTag cache)throws Exception{
        yes(journal(store).pending().isEmpty(),"Recovery left main transaction pending");
        yes(store.ledger().current().orElseThrow().frame().snapshot().state().equals(decision==Decision.COMMIT?after():State.empty()),"Recovery chose incorrect quest progress");
        yes(transaction.decisionFor(LegacyPlayerDataFileReader.read(playerFile(store.worldDirectory()).getParent(),ACTOR).data())==decision,"Mirror does not follow world outcome");
        if(cache!=null){yes(transaction.decisionFor(cache)==decision && cache.getString("loaded:extra").equals("retain-cache"),"Cache outcome or opaque fields changed");}
    }
    static void admit(LegacyQuestWorldStore store,CompoundTag cache)throws Exception{
        LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{});
        LegacyPlayerMarkerUpgradeCheck.admission(store,Authority.WORLD_PLAYER,cache);
    }
    static void combinations(Path root)throws Exception{
        for(boolean history:List.of(false,true))for(Decision world:Decision.values())for(Decision mirror:Decision.values())for(Decision memory:Decision.values()){
            Path dir=root.resolve(history+"-"+world+"-"+mirror+"-"+memory);try(var store=create(dir)){
                Pending pending=prepare(store,history);files(dir,pending,world,mirror);byte[] before=Files.readAllBytes(playerFile(dir)),primary=Files.readAllBytes(dir.resolve("level.dat"));CompoundTag cache=cache(pending,memory);var head=store.ledger().current().orElseThrow();
                Files.write(dir.resolve("level.dat_old"),new byte[]{5,8,-1});Files.write(playerFile(dir).resolveSibling(ACTOR+".dat_old"),new byte[]{2,-7});List<LegacyOwnerTransactionRecovery.Boundary> points=new ArrayList<>();
                yes(LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{},points::add),"Owner recovery did not run");finalState(store,pending,world,cache);
                yes(store.ledger().current().orElseThrow().frame().revision()==head.frame().revision()+1,"Main ledger gained multiple decisions");
                yes(Arrays.equals(primary,Files.readAllBytes(dir.resolve("level.dat"))),"Recovery rewrote authoritative world bytes");
                yes(Arrays.equals(gunzip(Files.readAllBytes(playerFile(dir))),golden(chosen(pending,world),false,history && world==Decision.ABORT && mirror==Decision.COMMIT)),"Mirror differs from independent binary golden target: "+dir.getFileName());
                if(world==mirror)yes(Arrays.equals(before,Files.readAllBytes(playerFile(dir))),"Already matching mirror was recompressed");
                yes(Arrays.equals(Files.readAllBytes(dir.resolve("level.dat_old")),new byte[]{5,8,-1}) && Arrays.equals(Files.readAllBytes(playerFile(dir).resolveSibling(ACTOR+".dat_old")),new byte[]{2,-7}),"Recovery changed backups");
                archived(dir,primary);archived(dir,before);archived(dir,Files.readAllBytes(playerFile(dir)));var snapshot=bytes(dir);yes(!LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"Finished recovery repeated");yes(bytes(dir).equals(snapshot),"No-op changed history or files");
                yes(points.indexOf(LegacyOwnerTransactionRecovery.Boundary.CACHE_UPDATED)<points.indexOf(LegacyOwnerTransactionRecovery.Boundary.MAIN_RESOLVED),"Main decision preceded cache agreement");admit(store,cache);
                // Historical repair remains valid across later transactions.
                var current=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data();var next=current.copy();next.getList("Inventory",10).getCompound(0).putInt("count",1);
                var p=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,current,next,after(),Authority.WORLD_PLAYER);writeWorld(dir,p.playerDataToPersist());writePlayer(dir,p.playerDataToPersist());store.recoverOffline(()->{});
                yes(!LegacyOwnerTransactionRecovery.recover(store,null,()->{}),"Historical repair rejected later transaction");
            }
        }
    }
    static void unknowns(Path root)throws Exception{
        for(int scenario=0;scenario<7;scenario++){
            Path dir=root.resolve("bad-"+scenario);try(var store=create(dir)){
                var p=prepare(store,false);files(dir,p,Decision.ABORT,Decision.COMMIT);var cache=cache(p,Decision.COMMIT);int selected=scenario;
                switch(scenario){case 0->{var changed=p.originalPlayerData();changed.getList("Inventory",10).getCompound(0).putInt("count",99);writeWorld(dir,changed);}case 1->{var changed=p.playerDataToPersist();changed.getList("Inventory",10).getCompound(0).putInt("count",99);writePlayer(dir,changed);}case 2->cache.getList("Inventory",10).getCompound(0).putInt("count",99);case 3->cache.putUUID("UUID",OTHER);case 4->Files.delete(playerFile(dir));case 5->Files.write(dir.resolve("level.dat"),new byte[]{1,2});case 6->Files.write(playerFile(dir),new byte[]{3,4});}
                var before=bytes(dir);no(()->LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"unknown owner state "+selected);yes(bytes(dir).equals(before),"Rejected recovery changed files");
            }
        }
        Path dir=root.resolve("null-cache");try(var store=create(dir)){var p=prepare(store,false);files(dir,p,Decision.COMMIT,Decision.ABORT);var before=bytes(dir);no(()->LegacyOwnerTransactionRecovery.recover(store,()->null,()->{}),"missing loaded cache");yes(bytes(dir).equals(before),"Missing cache wrote data");}
    }
    static void interruptions(Path root)throws Exception{
        for(String scenario:List.of("world","mirror","cache","resolved-cache","main")){
            Path dir=root.resolve(scenario);try(var store=create(dir)){
                var p=prepare(store,false);files(dir,p,Decision.ABORT,Decision.COMMIT);var cache=cache(p,Decision.COMMIT);byte[] oldWorld=Files.readAllBytes(dir.resolve("level.dat")),oldMirror=Files.readAllBytes(playerFile(dir));
                no(()->LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{},point->{
                    if(point==LegacyOwnerTransactionRecovery.Boundary.PREPARED && scenario.equals("world")){var changed=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).documentData();changed.getCompound("Data").putLong("Time",444);NbtIo.writeCompressed(changed,dir.resolve("level.dat"));}
                    if(point==LegacyOwnerTransactionRecovery.Boundary.MIRROR_PUBLISHED && scenario.equals("mirror")){var changed=LegacyPlayerDataFileReader.read(playerFile(dir).getParent(),ACTOR).data();changed.putString("new_mod","retain");writePlayer(dir,changed);}
                    if((point==LegacyOwnerTransactionRecovery.Boundary.CACHE_UPDATED && scenario.equals("cache")) || (point==LegacyOwnerTransactionRecovery.Boundary.MAIN_RESOLVED && scenario.equals("resolved-cache"))){var changed=cache(p,Decision.COMMIT);cache.put("Inventory",changed.get("Inventory").copy());cache.put("NeoForgeData",changed.getCompound("NeoForgeData").copy());}
                    if(point==LegacyOwnerTransactionRecovery.Boundary.MAIN_RESOLVED && scenario.equals("main")){var a=p.originalPlayerData();var b=a.copy();b.getList("Inventory",10).getCompound(0).putInt("count",1);journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,a,b,after());}
                }),"external change at "+scenario);
                if(scenario.equals("world") || scenario.equals("mirror") || scenario.equals("main")){var preserved=bytes(dir);no(()->LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"unknown persisted state after "+scenario);yes(bytes(dir).equals(preserved),"Resume overwrote unknown data");}
                if(scenario.equals("main"))continue;
                if(scenario.equals("world"))Files.write(dir.resolve("level.dat"),oldWorld);if(scenario.equals("mirror"))Files.write(playerFile(dir),oldMirror);
                yes(LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"Restored exact file state did not resume");finalState(store,p,Decision.ABORT,cache);archived(dir,oldWorld);archived(dir,oldMirror);admit(store,cache);
            }
        }
    }
    static void crossMaintenance(Path root)throws Exception{
        Path dir=root.resolve("owner-pending");try(var store=create(dir)){
            var p=prepare(store,true);files(dir,p,Decision.ABORT,Decision.COMMIT);var cache=cache(p,Decision.COMMIT);byte[] primary=Files.readAllBytes(dir.resolve("level.dat"));
            LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{},point->{if(point==LegacyOwnerTransactionRecovery.Boundary.MAIN_RESOLVED){
                try{LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{});throw new AssertionError("Marker promotion overlapped unresolved owner recovery");}catch(IOException expected){assertions++;}
                yes(!LegacyPlayerMarkerUpgrade.resume(store,()->cache,()->{}),"Absent marker intent should allow startup owner recovery");
            }});yes(Arrays.equals(primary,Files.readAllBytes(dir.resolve("level.dat"))),"Overlapping maintenance rewrote authority");admit(store,cache);
        }
        Path dir2=root.resolve("marker-pending");try(var store=create(dir2)){
            var modern=LegacyPlayerMarkerUpgradeCheck.setup(store,Authority.WORLD_PLAYER);var cache=LegacyPlayerMarkerUpgradeCheck.legacy(modern);
            no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{},point->{if(point==LegacyPlayerMarkerUpgrade.Boundary.PREPARED){var after=cache.copy();after.getList("Inventory",10).getCompound(0).putInt("count",1);journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,cache,after,after(),Authority.WORLD_PLAYER);}}),"illegal main preparation during marker maintenance");
            var before=bytes(dir2);no(()->LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"owner repair while marker intent pending");yes(bytes(dir2).equals(before),"Owner repair changed overlapping marker operation");
        }
    }
    static void absentContainers(Path root)throws Exception{
        for(boolean forge:List.of(false,true)){
            Path dir=root.resolve("forge-"+forge);try(var store=create(dir)){
                var p=prepare(store,false);var before=rich(5);if(!forge)before.remove("NeoForgeData");before.putString("only_mirror","retained");writePlayer(dir,before);writeWorld(dir,p.playerDataToPersist());var cache=cache(p,Decision.ABORT);byte[] primary=Files.readAllBytes(dir.resolve("level.dat"));
                yes(LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"Missing marker containers not repaired");finalState(store,p,Decision.COMMIT,cache);
                yes(LegacyPlayerDataFileReader.read(playerFile(dir).getParent(),ACTOR).data().getString("only_mirror").equals("retained"),"Container creation replaced foreign mirror fields");yes(Arrays.equals(primary,Files.readAllBytes(dir.resolve("level.dat"))),"Container repair rewrote world");admit(store,cache);
            }
        }
    }
    static void integrity(Path root)throws Exception{
        Path dir=root.resolve("blob");try(var store=create(dir)){var p=prepare(store,false);files(dir,p,Decision.COMMIT,Decision.ABORT);byte[] old=Files.readAllBytes(playerFile(dir));LegacyOwnerTransactionRecovery.recover(store,null,()->{});Path blob=recovery(dir).resolve("blobs").resolve(LegacyQuestProgressStoreCodec.digest(old)+".gz");Files.write(blob,new byte[]{1,2});var before=bytes(dir);no(()->LegacyOwnerTransactionRecovery.recover(store,null,()->{}),"corrupt historical owner archive");yes(bytes(dir).equals(before),"Corrupt archive silently replaced");}
        Path missing=root.resolve("missing");try(var store=create(missing)){var p=prepare(store,false);files(missing,p,Decision.ABORT,Decision.COMMIT);LegacyOwnerTransactionRecovery.recover(store,null,()->{});Path ledger=recovery(missing).resolve("ledger");Files.move(ledger,root.resolve("held-ledger"));no(()->LegacyOwnerTransactionRecovery.recover(store,null,()->{}),"missing auxiliary history");yes(!Files.exists(ledger),"Recovery reset missing history");}
        Path fresh=root.resolve("none");try(var store=create(fresh)){var before=bytes(fresh);yes(!LegacyOwnerTransactionRecovery.recover(store,null,()->{}),"No transaction generated repair");yes(bytes(fresh).equals(before),"No-op created maintenance directory");}
        Path uuid=root.resolve("uuid");try(var store=create(uuid)){journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,rich(5),rich(3),after());var before=bytes(uuid);yes(!LegacyOwnerTransactionRecovery.recover(store,null,()->{}),"UUID transaction entered owner recovery");yes(bytes(uuid).equals(before),"UUID transaction changed");}
    }
    static void process(String mode,Path dir,Decision decision,String boundary)throws Exception{
        if(mode.equals("crash"))try(var store=create(dir)){
            var p=prepare(store,true);files(dir,p,decision,other(decision));Files.write(dir.resolve("fixture-world.gz"),Files.readAllBytes(dir.resolve("level.dat")));Files.write(dir.resolve("fixture-mirror.gz"),Files.readAllBytes(playerFile(dir)));var cache=cache(p,other(decision));
            LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{},point->{if(point.name().equals(boundary))Runtime.getRuntime().halt(73);});throw new AssertionError("Missing owner recovery halt");
        }
        try(var store=LegacyQuestWorldStore.open(dir)){
            var cache=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data();cache.putString("loaded:extra","retain-cache");LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{});
            yes(journal(store).pending().isEmpty(),"Restart left pending main transaction");yes(store.ledger().current().orElseThrow().frame().snapshot().state().equals(decision==Decision.COMMIT?after():State.empty()),"Restart chose wrong quest state");
            yes(Arrays.equals(Files.readAllBytes(dir.resolve("fixture-world.gz")),Files.readAllBytes(dir.resolve("level.dat"))),"Restart rewrote authoritative world");archived(dir,Files.readAllBytes(dir.resolve("fixture-world.gz")));archived(dir,Files.readAllBytes(dir.resolve("fixture-mirror.gz")));
            yes(!LegacyOwnerTransactionRecovery.recover(store,()->cache,()->{}),"Restart left pending repair");admit(store,cache);
        }System.out.println("OWNER_RESTART_ADMISSION_OK");
    }
    public static void main(String[] args)throws Exception{
        if(args.length>1){process(args[0],Path.of(args[1]),Decision.valueOf(args[2]),args[3]);return;}
        Path root=Path.of(args[0]);combinations(root.resolve("combinations"));unknowns(root.resolve("unknowns"));interruptions(root.resolve("interruptions"));crossMaintenance(root.resolve("cross-maintenance"));absentContainers(root.resolve("containers"));integrity(root.resolve("integrity"));
        System.out.println("{\"test\":\"world_authoritative_owner_transaction_recovery\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_call_sites_registered\":true,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0,\"outcome_mirror_cache_combinations\":16,\"binary_golden_verified\":true}");
    }
}

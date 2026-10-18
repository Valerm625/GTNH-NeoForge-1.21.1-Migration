package org.gtnhmigration.compat;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Decision;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;

/** Temporary world directories, synthetic players; no game or native caches. */
public final class LegacyQuestWorldStoreCheck {
    static int checks;
    interface Operation{void run()throws Exception;}
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void no(Operation action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){checks++;return;}throw new AssertionError("Accepted "+message);}
    static Path metadata(Path world){return world.resolve(LegacyQuestWorldStore.DIRECTORY);}
    static Path manifest(Path world){return metadata(world).resolve(LegacyQuestWorldStore.MANIFEST);}
    static State after(){return new State(Map.of(),Map.of(KEY,new LegacyRetrievalEngine.State(Map.of(ACTOR,List.of(2)),Set.of(ACTOR))));}
    static LegacyQuestWorldStore create(Path world)throws Exception{Files.createDirectories(world);return LegacyQuestWorldStore.create(world,BINDING,CATALOG,State.empty(),List.of(new SourceDocument("original-import",new byte[]{0,-1,3})));}
    static LegacyRetrievalTransactionJournal journal(LegacyQuestWorldStore world){return new LegacyRetrievalTransactionJournal(world.ledger(),world.binding(),world.catalog());}
    static void writePlayer(Path world,CompoundTag p)throws IOException{Path players=world.resolve("playerdata");Files.createDirectories(players);NbtIo.writeCompressed(p,players.resolve(ACTOR+".dat"));}
    static void writeWorld(Path world,CompoundTag p)throws IOException{CompoundTag root=new CompoundTag(),data=new CompoundTag();data.put("Player",p);data.putLong("Time",123456789L);data.putByteArray("other:opaque",new byte[]{-1,0,3});root.put("Data",data);NbtIo.writeCompressed(root,world.resolve("level.dat"));}
    static Map<String,String> bytes(Path directory)throws IOException{
        Map<String,String> result=new TreeMap<>();try(var paths=Files.walk(directory)){for(Path path:paths.filter(Files::isRegularFile).toList())result.put(directory.relativize(path).toString(),path.getFileName().toString().equals("ledger.lock")?"exclusive-lock":LegacyQuestProgressStoreCodec.digest(Files.readAllBytes(path)));}return result;
    }
    static void lifecycle(Path root)throws Exception{
        Path world=root.resolve("first");UUID id;String head;byte[] raw;
        try(var store=create(world)){
            id=store.storageId();head=store.ledger().current().orElseThrow().sha256();raw=Files.readAllBytes(manifest(world));
            ok(store.worldDirectory().equals(world.toAbsolutePath().normalize()),"Wrong world path");
            ok(store.binding().equals(BINDING) && catalogSha256(store.catalog()).equals(catalogSha256(CATALOG)),"Historical catalog changed");
            var sources=store.ledger().current().orElseThrow().frame().snapshot().sources();
            ok(sources.size()==2 && Arrays.equals(sources.getFirst().bytes(),raw) && Arrays.equals(sources.get(1).bytes(),new byte[]{0,-1,3}),"World or original source bytes lost");
            LegacyQuestWorldStore.requireBoundStore(world,store.ledger(),BINDING,CATALOG);store.verifyBinding();
            no(()->LegacyQuestWorldStore.open(world),"second mounted handle");
            no(()->LegacyQuestWorldStore.requireBoundStore(root,store.ledger(),BINDING,CATALOG),"wrong server world");
            no(()->store.verifyCurrent(new Binding("c".repeat(64),"b".repeat(64)),CATALOG),"changed definitions");
            no(()->store.verifyCurrent(new Binding("a".repeat(64),"c".repeat(64)),CATALOG),"changed identities");
            no(()->store.verifyCurrent(BINDING,new Catalog(List.of(QUEST),List.of(new Definition(KEY,2)))),"changed catalog with same binding digests");
            ok(store.recoverOffline(()->{}).isEmpty(),"No-pending recovery created a decision");
        }
        try(var store=LegacyQuestWorldStore.open(world,BINDING,CATALOG)){
            ok(store.storageId().equals(id) && store.ledger().current().orElseThrow().sha256().equals(head),"Reopen replaced origin or history");
            no(()->create(world),"create overwrites existing world");
        }
        // Whole-world moves keep the same binding; no machine-specific path is serialized.
        Path moved=root.resolve("moved");Files.move(world,moved);
        try(var store=LegacyQuestWorldStore.open(moved)){ok(store.storageId().equals(id),"World move lost storage identity");}
        ok(Arrays.equals(raw,Files.readAllBytes(manifest(moved))),"Reopen rewrote manifest");
        no(()->LegacyQuestWorldStore.open(moved,new Binding("c".repeat(64),"b".repeat(64)),CATALOG),"current definition mismatch");
        try(var store=LegacyQuestWorldStore.open(moved)){ok(store.storageId().equals(id),"Failed config check leaked lock");}
        Path absent=root.resolve("absent");no(()->LegacyQuestWorldStore.open(absent),"missing world");ok(!Files.exists(absent),"Open created missing world");
        Path empty=root.resolve("empty");Files.createDirectory(empty);no(()->LegacyQuestWorldStore.open(empty),"unbound world");ok(bytes(empty).isEmpty() && !Files.exists(metadata(empty)),"Open invented new ledger");
        Path reserved=root.resolve("reserved");Files.createDirectory(reserved);
        no(()->LegacyQuestWorldStore.create(reserved,BINDING,CATALOG,State.empty(),List.of(new SourceDocument("gtnh-world-binding:v1/foreign",new byte[]{1}))),"reserved source identity");ok(!Files.exists(metadata(reserved)),"Invalid create left metadata");
    }
    static void damaged(Path root)throws Exception{
        Path world=root.resolve("malformed");try(var ignored=create(world)){}byte[] good=Files.readAllBytes(manifest(world));
        // Header/version, truncation, trailing fields, digest, counts, task shape,
        // malformed UTF-8 and requirement count must never be silently dropped.
        List<byte[]> bads=new ArrayList<>();byte[] bad=good.clone();bad[7]='2';bads.add(bad);bads.add(Arrays.copyOf(good,good.length-1));bads.add(Arrays.copyOf(good,good.length+1));
        bad=good.clone();bad[24]='Z';bads.add(bad);bad=good.clone();ByteBuffer.wrap(bad).putInt(152,-1);bads.add(bad);
        bad=good.clone();ByteBuffer.wrap(bad).putInt(152,Integer.MAX_VALUE);bads.add(bad);
        bad=good.clone();ByteBuffer.wrap(bad).putInt(192,-1);bads.add(bad); // task index
        bad=good.clone();ByteBuffer.wrap(bad).putInt(196,Integer.MAX_VALUE);bads.add(bad); // factory length
        bad=good.clone();bad[200]=(byte)0xff;bads.add(bad);
        bad=good.clone();ByteBuffer.wrap(bad).putInt(bad.length-4,0);bads.add(bad);
        for(byte[] value:bads){Files.write(manifest(world),value);var before=bytes(world);no(()->LegacyQuestWorldStore.open(world),"invalid manifest");ok(bytes(world).equals(before),"Rejected manifest changed files");}
        Files.write(manifest(world),good);
        try(var store=LegacyQuestWorldStore.open(world)){
            byte[] changed=good.clone();changed[8]^=1;Files.write(manifest(world),changed);no(store::verifyBinding,"in-memory mounted manifest drift");Files.write(manifest(world),good);
            Path unknown=metadata(world).resolve("unknown.bin");Files.write(unknown,new byte[]{8});no(store::verifyBinding,"unknown metadata");Files.delete(unknown);
        }
        Path missing=root.resolve("missing");try(var ignored=create(missing)){}Path led=metadata(missing).resolve(LegacyQuestWorldStore.LEDGER);
        try(var files=Files.list(led)){for(Path p:files.filter(p->p.toString().endsWith(".nbt")).toList())Files.delete(p);}
        var before=bytes(missing);no(()->LegacyQuestWorldStore.open(missing),"missing genesis");ok(bytes(missing).equals(before),"Missing genesis became empty progress");
        Path noDir=root.resolve("no-ledger-dir");try(var ignored=create(noDir)){}Path ledgerDir=metadata(noDir).resolve(LegacyQuestWorldStore.LEDGER);
        Files.move(ledgerDir,root.resolve("held-ledger"));no(()->LegacyQuestWorldStore.open(noDir),"missing ledger directory");ok(!Files.exists(ledgerDir),"Open recreated missing ledger directory");
        Path one=root.resolve("one"),two=root.resolve("two");try(var a=create(one);var b=create(two)){
            no(()->LegacyQuestWorldStore.requireBoundStore(one,b.ledger(),BINDING,CATALOG),"foreign handle with same config");
        }
        byte[] first=Files.readAllBytes(manifest(one));Files.write(manifest(two),first);var foreign=bytes(two);no(()->LegacyQuestWorldStore.open(two),"foreign manifest with matching catalog");ok(bytes(two).equals(foreign),"Foreign manifest refusal changed data");
        Path late=root.resolve("late-binding");Files.createDirectories(metadata(late));Files.write(manifest(late),first);
        try(var ledger=LegacyQuestProgressFileStore.open(metadata(late).resolve(LegacyQuestWorldStore.LEDGER),BINDING,CATALOG)){
            var genesis=ledger.commit(Optional.empty(),State.empty(),List.of());
            UUID id=new UUID(ByteBuffer.wrap(first).getLong(8),ByteBuffer.wrap(first).getLong(16));
            ledger.commit(Optional.of(genesis.sha256()),State.empty(),List.of(new SourceDocument("gtnh-world-binding:v1/"+id,first)));
            no(()->LegacyQuestWorldStore.requireBoundStore(late,ledger,BINDING,CATALOG),"world binding appended after genesis");
        }
        // An independently forged ledger cannot advertise a different decoder catalog.
        Path forged=root.resolve("forged");Files.createDirectories(metadata(forged));Files.write(manifest(forged),first);
        Catalog wrong=new Catalog(List.of(QUEST),List.of(new Definition(KEY,2)));
        try(var ledger=LegacyQuestProgressFileStore.open(metadata(forged).resolve(LegacyQuestWorldStore.LEDGER),BINDING,wrong)){
            UUID id=new UUID(ByteBuffer.wrap(first).getLong(8),ByteBuffer.wrap(first).getLong(16));
            ledger.commit(Optional.empty(),State.empty(),List.of(new SourceDocument("gtnh-world-binding:v1/"+id,first)));
            no(()->LegacyQuestWorldStore.requireBoundStore(forged,ledger,BINDING,CATALOG),"different handle decoder catalog");
        }
    }
    static void recovery(Path root)throws Exception{
        for(Authority authority:Authority.values())for(boolean commit:List.of(false,true)){
            Path world=root.resolve(authority+"-"+commit);CompoundTag saved;
            try(var store=create(world)){
                var pending=journal(store).prepare(store.ledger().current().map(LegacyQuestProgressFileStore.Head::sha256),ACTOR,player(5),player(3),after(),authority);
                saved=commit?pending.playerDataToPersist():pending.originalPlayerData();writePlayer(world,saved);writeWorld(world,saved);
            }
            byte[] player=Files.readAllBytes(world.resolve("playerdata").resolve(ACTOR+".dat")),level=Files.readAllBytes(world.resolve("level.dat"));
            try(var store=LegacyQuestWorldStore.open(world)){
                int[] barriers={0};var result=store.recoverOffline(()->barriers[0]++).orElseThrow();
                ok(result.decision()==(commit?Decision.COMMIT:Decision.ABORT),"Wrong authority decision");ok(barriers[0]==2,"Recovery missed exclusion checks");
                ok(store.ledger().current().orElseThrow().frame().snapshot().state().equals(commit?after():State.empty()),"Wrong recovered progress");
                ok(store.recoverOffline(()->{}).isEmpty(),"Recovery repeated decision");journal(store).verifyResolvedPlayer(saved);
            }
            ok(Arrays.equals(player,Files.readAllBytes(world.resolve("playerdata").resolve(ACTOR+".dat"))) && Arrays.equals(level,Files.readAllBytes(world.resolve("level.dat"))),"Recovery rewrote player or world");
        }
        for(int scenario=0;scenario<7;scenario++){
            Path world=root.resolve("conflict-"+scenario);
            try(var store=create(world)){
                var p=journal(store).prepare(store.ledger().current().map(LegacyQuestProgressFileStore.Head::sha256),ACTOR,player(5),player(3),after(),Authority.WORLD_PLAYER);
                writeWorld(world,p.playerDataToPersist());writePlayer(world,p.playerDataToPersist());
                switch(scenario){
                    case 0->writePlayer(world,p.originalPlayerData());
                    case 1->Files.delete(world.resolve("level.dat"));
                    case 2->Files.delete(world.resolve("playerdata").resolve(ACTOR+".dat"));
                    case 3->{CompoundTag unknown=p.playerDataToPersist();unknown.getList("Inventory",10).getCompound(0).putInt("count",99);writePlayer(world,unknown);writeWorld(world,unknown);}
                    case 4->Files.write(world.resolve("level.dat"),new byte[]{1,2,3});
                }
                var before=bytes(world);String head=store.ledger().current().orElseThrow().sha256();int[] calls={0};int index=scenario;
                no(()->store.recoverOffline(()->{calls[0]++;if(calls[0]==2){try{
                    if(index==5){CompoundTag changed=p.playerDataToPersist();changed.putLong("external",4);writePlayer(world,changed);}
                    if(index==6){byte[] raw=Files.readAllBytes(manifest(world));raw[8]^=1;Files.write(manifest(world),raw);}
                }catch(IOException e){throw new UncheckedIOException(e);}}}),"conflict or evidence changed during recovery "+scenario);
                ok(store.ledger().current().orElseThrow().sha256().equals(head) && journal(store).pending().isPresent(),"Rejected recovery advanced ledger");
                if(scenario<5)ok(bytes(world).equals(before),"Rejected recovery changed files");
            }
        }
    }
    static void child(String mode,Path world,String choice)throws Exception{
        if(mode.equals("init-crash")){
            Files.createDirectories(world);LegacyQuestWorldStore.create(world,BINDING,CATALOG,State.empty(),List.of(),point->{if(point.name().equals(choice))Runtime.getRuntime().halt(73);});throw new AssertionError("Missed init boundary");
        }
        if(mode.equals("recover-check")){
            boolean ready=choice.equals("GENESIS_PUBLISHED");if(ready){try(var store=LegacyQuestWorldStore.open(world)){ok(store.ledger().current().orElseThrow().frame().revision()==1,"Lost published genesis");}}
            else {var before=bytes(world);no(()->LegacyQuestWorldStore.open(world),"partial initialization");ok(bytes(world).equals(before),"Partial init changed");}
        }else if(mode.equals("pending-crash")){
            try(var store=create(world)){String[] parts=choice.split(":");Authority authority=Authority.valueOf(parts[0]);var p=journal(store).prepare(store.ledger().current().map(LegacyQuestProgressFileStore.Head::sha256),ACTOR,player(5),player(3),after(),authority);CompoundTag data=parts[1].equals("COMMIT")?p.playerDataToPersist():p.originalPlayerData();writePlayer(world,data);writeWorld(world,data);Runtime.getRuntime().halt(73);}
        }else if(mode.equals("pending-check")){
            try(var store=LegacyQuestWorldStore.open(world)){ok(store.recoverOffline(()->{}).orElseThrow().decision().name().equals(choice),"Fresh JVM lost pending decision");ok(store.recoverOffline(()->{}).isEmpty(),"Fresh JVM duplicated recovery");}
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length>1){child(args[0],Path.of(args[1]),args[2]);System.out.println("WORLD_STORE_CHILD_OK");return;}
        Path root=Path.of(args[0]);lifecycle(root.resolve("lifecycle"));damaged(root.resolve("damaged"));recovery(root.resolve("recovery"));
        System.out.println("{\"test\":\"world_bound_quest_store\",\"passed\":true,\"assertions\":"+checks+",\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0,\"native_admission_verified\":false}");
    }
}

package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.gtnhmigration.compat.LegacyMaintenanceInitialization.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Decision;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.State;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;
import static org.gtnhmigration.compat.LegacyQuestWorldStoreCheck.*;

/** Construction failures and fresh-process restart, then real maintenance and
 * admission models; all player data is synthetic, no game objects are executed. */
public final class LegacyMaintenanceInitializationCheck {
    static int assertions,serial;static Path root;
    interface Action{void run()throws Exception;}
    static void yes(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void no(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalStateException|IllegalArgumentException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static Path next(){return root.resolve("world-"+(serial++));}
    static Map<String,String> files(Path path)throws Exception{
        var result=new TreeMap<String,String>();try(var paths=Files.walk(path)){for(Path p:paths.filter(Files::isRegularFile).toList())if(!p.getFileName().toString().equals("ledger.lock"))result.put(path.relativize(p).toString(),LegacyQuestProgressStoreCodec.digest(Files.readAllBytes(p)));}return result;
    }
    static void retained(Path path,Map<String,String> before)throws Exception{var after=files(path);for(var entry:before.entrySet())yes(entry.getValue().equals(after.get(entry.getKey())),"Lost/changed original "+entry.getKey());}
    static void seedIntent(LegacyQuestWorldStore store,Kind kind)throws Exception{Files.write(LegacyMaintenanceInitialization.intent(store,kind),LegacyMaintenanceInitialization.intentBytes(store,kind));}
    static void seedComplete(LegacyQuestWorldStore store,Kind kind)throws Exception{Files.write(LegacyMaintenanceInitialization.complete(store,kind),LegacyMaintenanceInitialization.completeBytes(LegacyMaintenanceInitialization.intentBytes(store,kind)));}
    static void emptyRoot(LegacyQuestWorldStore store,Kind kind)throws Exception{Path dir=LegacyMaintenanceInitialization.root(store,kind);Files.createDirectories(dir.resolve("blobs"));Files.createDirectories(dir.resolve("ledger"));}
    static void initialized(LegacyQuestWorldStore store,Kind kind)throws Exception{
        try(var auxiliary=LegacyMaintenanceInitialization.open(store,kind,true)){
            var head=auxiliary.current().orElseThrow();yes(head.frame().revision()==1 && head.frame().snapshot().state().equals(State.empty()),"Construction invented progress");
            yes(head.frame().snapshot().sources().equals(List.of(LegacyMaintenanceInitialization.origin(store,kind))),"Construction lost world origin");
        }
    }
    static void exceptions()throws Exception{
        for(Kind kind:Kind.values())for(Boundary boundary:Boundary.values()){
            Path dir=next();try(var world=create(dir)){
                writePlayer(dir,player(5));writeWorld(dir,player(5));var head=world.ledger().current().orElseThrow();var original=files(dir);
                no(()->{try(var unused=LegacyMaintenanceInitialization.open(world,kind,true,point->{if(point==boundary)throw new IOException("injected "+point);})){}},"construction failure "+boundary);
                var interrupted=files(dir);initialized(world,kind);retained(dir,original);retained(dir,interrupted);
                yes(world.ledger().current().orElseThrow().equals(head),"Initialization advanced main progress");
                var before=files(dir);try(var reopened=LegacyMaintenanceInitialization.open(world,kind,false)){yes(reopened.current().orElseThrow().frame().revision()==1,"Reopen duplicated genesis");}yes(files(dir).equals(before),"Reopen rewrote auxiliary files");
            }
        }
    }
    static void legacy()throws Exception{
        for(Kind kind:Kind.values()){
            Path dir=next();try(var world=create(dir)){
                emptyRoot(world,kind);try(var auxiliary=LegacyQuestProgressFileStore.open(LegacyMaintenanceInitialization.root(world,kind).resolve("ledger"),world.binding(),world.catalog())){auxiliary.commit(Optional.empty(),State.empty(),List.of(LegacyMaintenanceInitialization.origin(world,kind)));}
                var before=files(dir);try(var opened=LegacyMaintenanceInitialization.open(world,kind,true)){yes(opened.current().isPresent(),"Legacy genesis missing");}yes(files(dir).equals(before),"Legacy reopen invented construction evidence");
                yes(kind==Kind.MARKER?!LegacyPlayerMarkerUpgrade.resume(world,null,()->{}):!LegacyOwnerTransactionRecovery.recover(world,null,()->{}),"Legacy empty journal performed an operation");
            }
        }
    }
    static void refuse()throws Exception{
        for(Kind kind:Kind.values())for(String damage:List.of("legacy_empty","legacy_no_blobs","legacy_no_genesis","bad_intent","foreign_intent","wrong_protocol","ready_only","bad_ready","ready_missing_root","ready_missing_genesis","operation_blobs","operation_history","unknown_entry","root_file","blobs_file","ledger_file","corrupt_genesis")){
            Path dir=next();try(var world=create(dir)){
                Path aux=LegacyMaintenanceInitialization.root(world,kind);Kind other=kind==Kind.MARKER?Kind.OWNER:Kind.MARKER;
                if(!damage.startsWith("legacy_") && !damage.equals("ready_only"))seedIntent(world,kind);
                switch(damage){
                    case "legacy_empty" -> Files.createDirectory(aux);
                    case "legacy_no_blobs" -> Files.createDirectories(aux.resolve("ledger"));
                    case "legacy_no_genesis" -> emptyRoot(world,kind);
                    case "bad_intent" -> Files.write(LegacyMaintenanceInitialization.intent(world,kind),new byte[]{0,1});
                    case "foreign_intent" -> {try(var foreign=create(next())){Files.write(LegacyMaintenanceInitialization.intent(world,kind),LegacyMaintenanceInitialization.intentBytes(foreign,kind));}}
                    case "wrong_protocol" -> Files.write(LegacyMaintenanceInitialization.intent(world,kind),LegacyMaintenanceInitialization.intentBytes(world,other));
                    case "ready_only" -> seedComplete(world,kind);
                    case "bad_ready" -> Files.write(LegacyMaintenanceInitialization.complete(world,kind),new byte[]{1});
                    case "ready_missing_root" -> seedComplete(world,kind);
                    case "ready_missing_genesis" -> {emptyRoot(world,kind);seedComplete(world,kind);}
                    case "operation_blobs" -> {emptyRoot(world,kind);Files.write(aux.resolve("blobs").resolve("a".repeat(64)+".gz"),new byte[]{1,2,3});}
                    case "operation_history" -> {
                        emptyRoot(world,kind);try(var ledger=LegacyQuestProgressFileStore.open(aux.resolve("ledger"),world.binding(),world.catalog())){
                            var sources=List.of(LegacyMaintenanceInitialization.origin(world,kind));var first=ledger.commit(Optional.empty(),State.empty(),sources);ledger.commit(Optional.of(first.sha256()),State.empty(),sources);
                        }
                    }
                    case "unknown_entry" -> {Files.createDirectory(aux);Files.write(aux.resolve("unknown"),new byte[]{4});}
                    case "root_file" -> Files.write(aux,new byte[]{4});
                    case "blobs_file" -> {Files.createDirectory(aux);Files.write(aux.resolve("blobs"),new byte[]{4});}
                    case "ledger_file" -> {Files.createDirectory(aux);Files.createDirectory(aux.resolve("blobs"));Files.write(aux.resolve("ledger"),new byte[]{4});}
                    case "corrupt_genesis" -> {initialized(world,kind);try(var entries=Files.list(aux.resolve("ledger"))){Path frame=entries.filter(p->p.toString().endsWith(".nbt")).findFirst().orElseThrow();Files.write(frame,new byte[]{1,2});}}
                    default -> throw new AssertionError(damage);
                }
                var before=files(dir);no(()->{try(var ignored=LegacyMaintenanceInitialization.open(world,kind,true)){}},damage);yes(files(dir).equals(before),"Rejected construction changed files: "+damage);
            }
        }
        // A valid completion prevents recreation even when all genesis frames disappeared.
        for(Kind kind:Kind.values())try(var world=create(next())){
            initialized(world,kind);Path ledger=LegacyMaintenanceInitialization.root(world,kind).resolve("ledger");
            try(var entries=Files.list(ledger)){for(Path frame:entries.filter(p->p.toString().endsWith(".nbt")).toList())Files.delete(frame);}
            var before=files(world.worldDirectory());no(()->{try(var ignored=LegacyMaintenanceInitialization.open(world,kind,false)){}},"deleted established history");yes(files(world.worldDirectory()).equals(before),"Deleted history reset");
        }
    }
    static void publicationInterference()throws Exception{
        for(Kind kind:Kind.values())for(boolean intentChanged:new boolean[]{false,true})try(var world=create(next())){
            var before=files(world.worldDirectory());
            no(()->{try(var ignored=LegacyMaintenanceInitialization.open(world,kind,true,point->{if(point==Boundary.COMPLETE_PUBLISHED)Files.write(intentChanged?LegacyMaintenanceInitialization.intent(world,kind):LegacyMaintenanceInitialization.complete(world,kind),new byte[]{9,8,7});})){}},"Published construction evidence changed");
            retained(world.worldDirectory(),before);var damaged=files(world.worldDirectory());
            no(()->{try(var ignored=LegacyMaintenanceInitialization.open(world,kind,false)){}},"Changed publication reopened");yes(files(world.worldDirectory()).equals(damaged),"Interference evidence discarded");
        }
    }
    static void writeBaseline(Path dir,Map<String,String> values)throws Exception{
        try(var out=new DataOutputStream(Files.newOutputStream(dir.resolveSibling("baseline.bin")))){out.writeInt(values.size());for(var e:values.entrySet()){out.writeUTF(e.getKey());out.writeUTF(e.getValue());}}
    }
    static Map<String,String> baseline(Path dir)throws Exception{
        var values=new TreeMap<String,String>();try(var in=new DataInputStream(Files.newInputStream(dir.resolveSibling("baseline.bin")))){int count=in.readInt();for(int i=0;i<count;i++)values.put(in.readUTF(),in.readUTF());yes(in.read()==-1,"Baseline trailing bytes");}return values;
    }
    static void setup(LegacyQuestWorldStore store,Kind kind)throws Exception{
        if(kind==Kind.MARKER)LegacyPlayerMarkerUpgradeCheck.setup(store,Authority.WORLD_PLAYER);
        else {var pending=LegacyOwnerTransactionRecoveryCheck.prepare(store,false);LegacyOwnerTransactionRecoveryCheck.files(store.worldDirectory(),pending,Decision.COMMIT,Decision.ABORT);}
    }
    static void restart(Path dir,Kind kind)throws Exception{
        try(var world=LegacyQuestWorldStore.open(dir)){
            var interrupted=files(dir);var head=world.ledger().current().orElseThrow();initialized(world,kind);retained(dir,baseline(dir));retained(dir,interrupted);yes(world.ledger().current().orElseThrow().equals(head),"Restart construction altered main head");
            CompoundTag cache=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data();
            if(kind==Kind.MARKER){yes(!LegacyPlayerMarkerUpgrade.resume(world,()->cache,()->{}),"Initialization invented pending marker operation");yes(LegacyPlayerMarkerUpgrade.promote(world,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{}),"Recovered initialization did not permit marker promotion");}
            else {yes(LegacyOwnerTransactionRecovery.recover(world,()->cache,()->{}),"Recovered initialization did not permit owner recovery");yes(journal(world).pending().isEmpty(),"Owner recovery did not resolve");}
            LegacyPlayerMarkerUpgradeCheck.admission(world,Authority.WORLD_PLAYER,cache);
            yes(LegacyRetrievalTransactionJournal.readMarker(cache).isPresent(),"Final marker absent");
        }
    }
    public static void main(String[] args)throws Exception{
        if(args[0].equals("crash")){
            Path dir=Path.of(args[1]);Kind kind=Kind.valueOf(args[2]);Boundary boundary=Boundary.valueOf(args[3]);
            try(var world=create(dir)){setup(world,kind);writeBaseline(dir,files(dir));try(var unused=LegacyMaintenanceInitialization.open(world,kind,true,point->{if(point==boundary)Runtime.getRuntime().halt(73);})){} }
            throw new AssertionError("Crash boundary not reached");
        }
        if(args[0].equals("recover")){restart(Path.of(args[1]),Kind.valueOf(args[2]));System.out.println("MAINTENANCE_INITIALIZATION_RESTART_OK");return;}
        root=Path.of(args[0]);Files.createDirectories(root);exceptions();legacy();refuse();publicationInterference();
        System.out.println("{\"test\":\"maintenance_initialization\",\"passed\":true,\"assertions\":"+assertions+",\"protocols\":2,\"exception_boundaries\":24,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}

package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Decision;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;

/** Synthetic level.dat and UUID.dat, real NBT codecs and durable journal. */
public final class LegacyWorldPlayerAuthorityCheck {
    static int checks;
    interface Operation{void run()throws Exception;}
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void no(Operation action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException expected){checks++;return;}throw new AssertionError("Accepted "+message);}
    static CompoundTag world(CompoundTag p){CompoundTag root=new CompoundTag(),data=new CompoundTag(),rules=new CompoundTag();rules.putString("keepInventory","false");data.put("GameRules",rules);data.putLong("Time",123456789);data.put("Player",p.copy());root.put("Data",data);root.putByteArray("other:root",new byte[]{0,-1,9});return root;}
    static void writeWorld(Path dir,CompoundTag p)throws IOException{Files.createDirectories(dir);NbtIo.writeCompressed(world(p),dir.resolve("level.dat"));}
    static byte[] encode(CompoundTag tag)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(out));return out.toByteArray();}
    static CompoundTag decode(byte[] bytes)throws IOException{return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)),NbtAccounter.create(16L*1024*1024));}
    static LegacyRetrievalTransactionJournal journal(LegacyQuestProgressFileStore store){return new LegacyRetrievalTransactionJournal(store,BINDING,CATALOG);}
    static State after(){return new State(Map.of(),Map.of(KEY,new LegacyRetrievalEngine.State(Map.of(ACTOR,List.of(2)),Set.of(ACTOR))));}
    static void reader(Path root)throws Exception{
        writeWorld(root,player(5));Path path=root.resolve("level.dat");byte[] raw=Files.readAllBytes(path);var snap=LegacyPlayerDataFileReader.readWorldPlayer(root,ACTOR);
        ok(snap.authority()==Authority.WORLD_PLAYER && snap.path().equals(path.toAbsolutePath()),"Wrong authority or path");
        ok(snap.data().equals(player(5)) && snap.documentData().equals(world(player(5))),"World or player data lost");
        ok(Arrays.equals(raw,snap.originalBytes()),"Compressed world bytes lost");
        snap.data().putString("changed","bad");snap.documentData().getCompound("Data").remove("GameRules");snap.originalBytes()[0]=0;
        ok(snap.data().equals(player(5)) && snap.documentData().equals(world(player(5))) && Arrays.equals(raw,snap.originalBytes()),"Snapshot aliases callers");snap.verifyUnchanged();
        CompoundTag changed=world(player(5));changed.getCompound("Data").putLong("Time",8);NbtIo.writeCompressed(changed,path);no(snap::verifyUnchanged,"world changed outside Player subtree");
        Files.createDirectories(root.resolve("playerdata"));NbtIo.writeCompressed(player(5),root.resolve("playerdata").resolve(ACTOR+".dat"));NbtIo.writeCompressed(world(player(5)),root.resolve("level.dat_old"));
        for(int scenario=0;scenario<6;scenario++){
            CompoundTag bad=world(player(5));switch(scenario){
                case 0 -> bad.remove("Data");case 1 -> bad.putString("Data","bad");
                case 2 -> bad.getCompound("Data").remove("Player");case 3 -> bad.getCompound("Data").putInt("Player",7);
                case 4 -> bad.getCompound("Data").getCompound("Player").putUUID("UUID",OTHER);
                case 5 -> bad.getCompound("Data").getCompound("Player").putString("Inventory","bad");
            }
            NbtIo.writeCompressed(bad,path);byte[] before=Files.readAllBytes(path);no(()->LegacyPlayerDataFileReader.readWorldPlayer(root,ACTOR),"bad world payload "+scenario);ok(Arrays.equals(before,Files.readAllBytes(path)),"Rejected world changed");
        }
        Files.write(path,new byte[]{1,2,3});no(()->LegacyPlayerDataFileReader.readWorldPlayer(root,ACTOR),"corrupt primary with valid old/world player mirrors");
        Files.delete(path);no(()->LegacyPlayerDataFileReader.readWorldPlayer(root,ACTOR),"missing primary with valid old/world player mirrors");
        ok(Files.exists(root.resolve("level.dat_old")) && Files.exists(root.resolve("playerdata").resolve(ACTOR+".dat")),"Fallback artifacts removed");
    }
    static void worldRecovery(Path root)throws Exception{
        Path players=root.resolve("playerdata"),ledger=root.resolve("ledger");Files.createDirectories(players);
        try(var s=LegacyQuestProgressFileStore.open(ledger,BINDING,CATALOG)){
            var j=journal(s);var pending=j.prepare(Optional.empty(),ACTOR,player(5),player(3),after(),Authority.WORLD_PLAYER);
            ok(pending.authority()==Authority.WORLD_PLAYER,"Prepared authority lost");
            CompoundTag record=decode(s.current().orElseThrow().frame().snapshot().sources().getLast().bytes());
            ok(record.getInt("schema_version")==2 && record.getString("player_authority").equals("WORLD_PLAYER"),"World authority not persisted");
            writeWorld(root,player(5));NbtIo.writeCompressed(pending.playerDataToPersist(),players.resolve(ACTOR+".dat"));String head=s.current().orElseThrow().sha256();
            no(()->j.resolve(pending.playerDataToPersist()),"bare NBT for world transaction");
            no(()->j.resolveFromPlayerDirectory(players),"UUID.dat mirror instead of authority");
            no(()->j.resolve(LegacyPlayerDataFileReader.read(players,ACTOR)),"typed mirror even with valid new marker");
            ok(s.current().orElseThrow().sha256().equals(head),"Wrong authority changed journal");
            byte[] before=Files.readAllBytes(root.resolve("level.dat"));ok(j.resolveFromWorldPlayer(root).decision()==Decision.ABORT,"Mirror caused false commit");
            ok(s.current().orElseThrow().frame().snapshot().state().equals(State.empty()) && Arrays.equals(before,Files.readAllBytes(root.resolve("level.dat"))),"World abort modified progress or metadata");
            var next=j.prepare(s.current().map(LegacyQuestProgressFileStore.Head::sha256),ACTOR,player(5),player(3),after(),Authority.WORLD_PLAYER);writeWorld(root,next.playerDataToPersist());
            NbtIo.writeCompressed(player(5),players.resolve(ACTOR+".dat"));
        }
        try(var s=LegacyQuestProgressFileStore.open(ledger,BINDING,CATALOG)){
            var j=journal(s);ok(j.pending().orElseThrow().authority()==Authority.WORLD_PLAYER,"Reopen forgot authority");
            var stale=LegacyPlayerDataFileReader.readWorldPlayer(root,ACTOR);CompoundTag changed=stale.documentData();changed.getCompound("Data").putString("extra:world","retain");NbtIo.writeCompressed(changed,root.resolve("level.dat"));String head=s.current().orElseThrow().sha256();
            no(()->j.resolve(stale),"stale world snapshot");ok(s.current().orElseThrow().sha256().equals(head),"Stale world snapshot advanced journal");
            byte[] before=Files.readAllBytes(root.resolve("level.dat")),mirror=Files.readAllBytes(players.resolve(ACTOR+".dat"));
            ok(j.resolveFromWorldPlayer(root).decision()==Decision.COMMIT,"Old UUID mirror caused false abort");
            ok(s.current().orElseThrow().frame().snapshot().state().equals(after()),"World progress did not commit");
            ok(Arrays.equals(before,Files.readAllBytes(root.resolve("level.dat"))) && Arrays.equals(mirror,Files.readAllBytes(players.resolve(ACTOR+".dat"))),"World recovery rewrote a save");
            ok(LegacyPlayerDataFileReader.readWorldPlayer(root,ACTOR).documentData().getCompound("Data").getString("extra:world").equals("retain"),"Opaque world metadata lost");
        }
    }
    static void legacyAndMutations(Path root)throws Exception{
        Path players=root.resolve("legacy/players"),world=root.resolve("legacy/world");Files.createDirectories(players);
        try(var s=LegacyQuestProgressFileStore.open(root.resolve("legacy/ledger"),BINDING,CATALOG)){
            var j=journal(s);var p=j.prepare(Optional.empty(),ACTOR,player(5),player(3),after());
            var record=decode(s.current().orElseThrow().frame().snapshot().sources().getLast().bytes());
            ok(record.getInt("schema_version")==1 && !record.contains("player_authority") && p.authority()==Authority.PLAYER_FILE,"Legacy preparation changed schema");
            NbtIo.writeCompressed(p.playerDataToPersist(),players.resolve(ACTOR+".dat"));writeWorld(world,p.playerDataToPersist());
            no(()->j.resolveFromWorldPlayer(world),"world for legacy UUID transaction");
            no(()->j.resolve(LegacyPlayerDataFileReader.readWorldPlayer(world,ACTOR)),"typed world for UUID transaction");
            ok(j.resolveFromPlayerDirectory(players).decision()==Decision.COMMIT,"Legacy UUID recovery broke");
        }
        SourceDocument source;
        try(var s=LegacyQuestProgressFileStore.open(root.resolve("template"),BINDING,CATALOG)){
            journal(s).prepare(Optional.empty(),ACTOR,player(5),player(3),after(),Authority.WORLD_PLAYER);source=s.current().orElseThrow().frame().snapshot().sources().getLast();
        }
        for(int scenario=0;scenario<4;scenario++){
            CompoundTag bad=decode(source.bytes());switch(scenario){case 0->bad.remove("player_authority");case 1->bad.putString("player_authority","PLAYER_FILE");case 2->bad.putInt("player_authority",2);case 3->bad.putInt("schema_version",3);}
            try(var s=LegacyQuestProgressFileStore.open(root.resolve("bad-"+scenario),BINDING,CATALOG)){
                s.commit(Optional.empty(),State.empty(),List.of(new SourceDocument(source.identity(),encode(bad))));String head=s.current().orElseThrow().sha256();
                no(()->journal(s).pending(),"malformed source authority "+scenario);ok(s.current().orElseThrow().sha256().equals(head),"Rejected source record changed");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);reader(root.resolve("reader"));worldRecovery(root.resolve("world"));legacyAndMutations(root.resolve("compat"));
        System.out.println("{\"test\":\"world_player_authority\",\"passed\":true,\"assertions\":"+checks+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_player_save_verified\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0}");
    }
}

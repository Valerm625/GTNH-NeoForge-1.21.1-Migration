package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

/** Real local files and Minecraft NBT, synthetic player contents; no Player. */
public final class LegacyPlayerDataFileReaderCheck {
    static int assertions;static boolean symbolicLinkChecked;
    static final UUID ACTOR=new UUID(1,2),OTHER=new UUID(3,4),QUEST=new UUID(0,2);
    static final TaskKey KEY=new TaskKey(QUEST,1,"bq_standard:retrieval");
    static final Catalog CATALOG=new Catalog(List.of(QUEST),List.of(new Definition(KEY,1)));
    static final Binding BINDING=new Binding("a".repeat(64),"b".repeat(64));
    interface Action{void run()throws Exception;}
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void rejects(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static CompoundTag player(int count){CompoundTag p=new CompoundTag();p.putUUID("UUID",ACTOR);ListTag list=new ListTag();CompoundTag entry=new CompoundTag();entry.putByte("Slot",(byte)0);entry.putString("id","minecraft:oak_log");entry.putInt("count",count);list.add(entry);p.put("Inventory",list);p.putLongArray("opaque",new long[]{Long.MIN_VALUE,Long.MAX_VALUE});return p;}
    static byte[] compressed(CompoundTag data)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(data,bytes);return bytes.toByteArray();}
    static Path file(Path directory){return directory.resolve(ACTOR+".dat");}
    static byte[] sha(Path path)throws Exception{return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));}
    static byte[] withHeader(byte[] original)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] header=Arrays.copyOf(original,10);header[3]=31;out.write(header);
        out.write(new byte[]{3,0,9,8,7});out.write("player.dat".getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.write(0);out.write(new byte[]{99,111,109,109,101,110,116,0});
        CRC32 crc=new CRC32();crc.update(out.toByteArray());out.write((int)crc.getValue()&255);out.write((int)(crc.getValue()>>>8)&255);
        out.write(original,10,original.length-10);return out.toByteArray();
    }
    static void reading(Path root)throws Exception{
        Files.createDirectories(root);CompoundTag p=player(5);byte[] good=compressed(p);Files.write(file(root),good);
        var snapshot=LegacyPlayerDataFileReader.read(root,ACTOR);
        check(snapshot.data().equals(p),"NBT data changed");check(Arrays.equals(snapshot.originalBytes(),good),"Original gzip bytes changed");
        byte[] alias=snapshot.originalBytes();alias[0]=0;check(snapshot.originalBytes()[0]==31,"Raw bytes alias caller");
        snapshot.data().getList("Inventory",10).clear();check(snapshot.data().equals(p),"NBT aliases caller");snapshot.verifyUnchanged();
        check(snapshot.path().equals(file(root).toAbsolutePath()),"Wrong authoritative path");
        byte[] optional=withHeader(good);Files.write(file(root),optional);var extended=LegacyPlayerDataFileReader.read(root,ACTOR);
        check(extended.data().equals(p) && Arrays.equals(extended.originalBytes(),optional),"Optional gzip headers changed");rejects(snapshot::verifyUnchanged,"recompressed file with same logical data");
        for(int scenario=0;scenario<8;scenario++){
            byte[] bad=good.clone();switch(scenario){
                case 0 -> bad[0]=0;
                case 1 -> bad[3]=(byte)128;
                case 2 -> bad[bad.length-8]^=1;
                case 3 -> bad[bad.length-4]^=1;
                case 4 -> bad=Arrays.copyOf(bad,bad.length-1);
                case 5 -> bad=Arrays.copyOf(bad,bad.length+1);
                case 6 -> {byte[] both=new byte[bad.length*2];System.arraycopy(bad,0,both,0,bad.length);System.arraycopy(bad,0,both,bad.length,bad.length);bad=both;}
                case 7 -> {bad=optional.clone();bad[10]^=1;}
            }
            Files.write(file(root),bad);byte[] before=sha(file(root));rejects(()->LegacyPlayerDataFileReader.read(root,ACTOR),"gzip corruption "+scenario);check(Arrays.equals(before,sha(file(root))),"Rejected file was changed");
        }
        CompoundTag foreign=p.copy();foreign.putUUID("UUID",OTHER);Files.write(file(root),compressed(foreign));rejects(()->LegacyPlayerDataFileReader.read(root,ACTOR),"foreign UUID");
        CompoundTag malformed=p.copy();malformed.putString("Inventory","bad");Files.write(file(root),compressed(malformed));rejects(()->LegacyPlayerDataFileReader.read(root,ACTOR),"invalid inventory type");
        ByteArrayOutputStream raw=new ByteArrayOutputStream();NbtIo.write(p,new DataOutputStream(raw));raw.write(7);ByteArrayOutputStream zip=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(zip)){gzip.write(raw.toByteArray());}Files.write(file(root),zip.toByteArray());rejects(()->LegacyPlayerDataFileReader.read(root,ACTOR),"trailing uncompressed NBT bytes");
        Files.write(file(root),good);rejects(()->LegacyPlayerDataFileReader.read(root,ACTOR,path->{Path replacement=root.resolve("replacement.tmp");Files.write(replacement,compressed(player(3)));Files.move(replacement,path,StandardCopyOption.REPLACE_EXISTING);}),"replacement while reading");
        Path oversized=root.resolveSibling("oversized");Files.createDirectories(oversized);try(RandomAccessFile sparse=new RandomAccessFile(file(oversized).toFile(),"rw")){sparse.setLength((long)LegacyPlayerDataFileReader.MAX_COMPRESSED_BYTES+1);}rejects(()->LegacyPlayerDataFileReader.read(oversized,ACTOR),"oversized compressed file");
        Path bomb=root.resolveSibling("expansion-limit");Files.createDirectories(bomb);try(GZIPOutputStream gzip=new GZIPOutputStream(Files.newOutputStream(file(bomb)))){byte[] block=new byte[8192];for(int n=0;n<=LegacyPlayerDataFileReader.MAX_DECOMPRESSED_BYTES/block.length;n++)gzip.write(block);}rejects(()->LegacyPlayerDataFileReader.read(bomb,ACTOR),"decompression size limit");
        Path directoryFile=root.resolveSibling("directory-file");Files.createDirectories(file(directoryFile));rejects(()->LegacyPlayerDataFileReader.read(directoryFile,ACTOR),"directory in place of player file");
        Path symlink=root.resolveSibling("symlink-file");Files.createDirectories(symlink);Files.write(root.resolve("valid.dat"),good);
        boolean created=false;try{Files.createSymbolicLink(file(symlink),root.resolve("valid.dat").toAbsolutePath());created=true;}catch(UnsupportedOperationException|FileSystemException unsupported){}
        if(created){rejects(()->LegacyPlayerDataFileReader.read(symlink,ACTOR),"symbolic player file");symbolicLinkChecked=true;}
    }
    static void recovery(Path root)throws Exception{
        Files.createDirectories(root);Path players=root.resolve("players");Files.createDirectories(players);CompoundTag before=player(5);
        var afterState=new State(Map.of(),Map.of(KEY,new LegacyRetrievalEngine.State(Map.of(ACTOR,List.of(2)),Set.of(ACTOR))));
        try(var store=LegacyQuestProgressFileStore.open(root.resolve("ledger"),BINDING,CATALOG)){
            var journal=new LegacyRetrievalTransactionJournal(store,BINDING,CATALOG);var pending=journal.prepare(Optional.empty(),ACTOR,before,player(3),afterState);String head=store.current().orElseThrow().sha256();
            Path old=players.resolve(ACTOR+".dat_old");Files.write(old,compressed(pending.playerDataToPersist()));byte[] backup=sha(old);
            rejects(()->journal.resolveFromPlayerDirectory(players),"missing primary with valid after-state backup");check(store.current().orElseThrow().sha256().equals(head),"Missing primary changed journal");
            Files.write(file(players),new byte[]{1,2,3});rejects(()->journal.resolveFromPlayerDirectory(players),"bad primary with valid backup");check(store.current().orElseThrow().sha256().equals(head),"Bad primary changed journal");
            Files.write(file(players),compressed(before));byte[] original=sha(file(players));check(journal.resolveFromPlayerDirectory(players).decision()==LegacyRetrievalTransactionJournal.Decision.ABORT,"Current before-file did not abort");check(Arrays.equals(original,sha(file(players))) && Arrays.equals(backup,sha(old)),"Recovery changed player or backup");
            var next=journal.prepare(store.current().map(LegacyQuestProgressFileStore.Head::sha256),ACTOR,before,player(3),afterState);Files.write(file(players),compressed(next.playerDataToPersist()));
        }
        try(var store=LegacyQuestProgressFileStore.open(root.resolve("ledger"),BINDING,CATALOG)){
            var journal=new LegacyRetrievalTransactionJournal(store,BINDING,CATALOG);byte[] saved=sha(file(players));check(journal.resolveFromPlayerDirectory(players).decision()==LegacyRetrievalTransactionJournal.Decision.COMMIT,"Authoritative after-file did not commit");check(store.current().orElseThrow().frame().snapshot().state().equals(afterState),"Wrong restored progress");check(Arrays.equals(saved,sha(file(players))),"Commit resolution rewrote player file");
            rejects(()->journal.resolveFromPlayerDirectory(players),"duplicate resolution");journal.verifyResolvedPlayer(LegacyPlayerDataFileReader.read(players,ACTOR).data());
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);reading(root.resolve("reading"));recovery(root.resolve("recovery"));
        System.out.println("{\"test\":\"authoritative_player_file_readback\",\"passed\":true,\"assertions\":"+assertions+",\"symbolic_link_checked\":"+symbolicLinkChecked+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_player_save_verified\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0}");
    }
}

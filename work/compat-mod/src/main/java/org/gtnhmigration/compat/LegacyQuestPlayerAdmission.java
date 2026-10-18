package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;

/** Source receipt for one native player load. This preserves the native
 * DataFixer/event path; it does not deserialize a Player or repair old markers.
 * Caller keeps a native save/lifecycle barrier across begin, load and finish.
 */
public final class LegacyQuestPlayerAdmission {
    private LegacyQuestPlayerAdmission(){}
    public static final class Receipt {
        private final Thread owner=Thread.currentThread();
        private final LegacyQuestWorldStore world;
        private final UUID actor;
        private final LegacyPlayerDataFileReader.Snapshot source;
        private final CompoundTag loadedWorldPlayer;
        private final String head;
        private int fileReads,payloads;
        private CompoundTag acceptedPayload;
        private boolean finished,failed;
        private Receipt(LegacyQuestWorldStore world,UUID actor,LegacyPlayerDataFileReader.Snapshot source,CompoundTag loadedWorldPlayer)throws IOException {
            this.world=world;this.actor=actor;this.source=source;this.loadedWorldPlayer=loadedWorldPlayer==null?null:loadedWorldPlayer.copy();
            head=world.ledger().current().orElseThrow().sha256();
        }
        public UUID actor(){return actor;}
        public Optional<Authority> authority(){return source==null?Optional.empty():Optional.of(source.authority());}
        private void require(boolean condition,String problem)throws IOException {if(!condition){failed=true;throw new IOException(problem);}}
        private void usable()throws IOException {require(Thread.currentThread()==owner && !finished && !failed,"Player admission receipt is no longer usable");}
        /** Used at the actual native compressed-file read call site. Returning
         * a copy preserves DataFixer input without rereading a different file.
         */
        public CompoundTag readNativeFile(Path path)throws IOException {
            try {
            usable();require(source!=null && source.authority()==Authority.PLAYER_FILE,"Unexpected player-file read during admission");
            require(path.toAbsolutePath().normalize().equals(source.path()) && fileReads==0,"Native load selected another file or read twice");
            verifyEvidence();fileReads++;return source.data();
            }catch(IOException|RuntimeException|Error failure){failed=true;throw failure;}
        }
        /** Called immediately before Entity.load; native DataFixer has already
         * executed for UUID files. No callback may consume a second payload.
         */
        public void acceptPayload(UUID player,CompoundTag data)throws IOException {
            try {
            usable();require(actor.equals(player) && payloads==0 && source!=null,"Unexpected native player payload");
            require(data.hasUUID("UUID") && data.getUUID("UUID").equals(actor),"Native payload UUID mismatch");
            require(source.authority()==Authority.WORLD_PLAYER?fileReads==0:fileReads==1,"Payload did not use its verified file source");
            if(loadedWorldPlayer!=null)require(loadedWorldPlayer.equals(data),"Native owner payload differs from verified loaded tag");
            journal(world).verifyResolvedPlayer(data);
            require(journal(world).proposePersistentMarker(data).equals(data),"Legacy transaction marker needs persistent promotion before admission");
            verifyEvidence();acceptedPayload=data;payloads++;
            }catch(IOException|RuntimeException|Error failure){failed=true;throw failure;}
        }
        public void allowEmptyFallback()throws IOException {
            try {
            usable();require(source==null,"Native backup/fallback after a verified primary failed");verifyEvidence();
            }catch(IOException|RuntimeException|Error failure){failed=true;throw failure;}
        }
        public void finish(Optional<CompoundTag> result,UUID actualPlayer,CompoundTag persistentData)throws IOException {
            try {
            usable();boolean saved=source!=null;
            require(result.isPresent()==saved && payloads==(saved?1:0),"Native load result does not match the verified source");
            require(!saved || result.orElseThrow()==acceptedPayload,"Native return value switched the admitted payload");
            require(actor.equals(actualPlayer),"Live player UUID changed during admission");
            CompoundTag live=new CompoundTag();live.putUUID("UUID",actualPlayer);live.put("NeoForgeData",persistentData.copy());
            journal(world).verifyResolvedPlayer(live);
            require(journal(world).proposePersistentMarker(live).equals(live),"Live transaction marker lost its persistent placement");
            verifyEvidence();finished=true;
            }catch(IOException|RuntimeException|Error failure){failed=true;throw failure;}
        }
        private void verifyEvidence()throws IOException {
            world.verifyBinding();require(world.ledger().current().orElseThrow().sha256().equals(head),"Ledger changed during native admission");
            if(source==null)requireAbsentPlayerFiles(world.worldDirectory(),actor);else source.verifyUnchanged();
        }
    }
    public static Receipt begin(LegacyQuestWorldStore world,UUID actor,boolean singleplayerOwner,CompoundTag loadedPlayer)throws IOException {
        Objects.requireNonNull(world);Objects.requireNonNull(actor);
        synchronized(world.ledger()){
            world.verifyBinding();var journal=journal(world);
            require(journal.pending().isEmpty(),"Pending world transaction must be recovered before loading any player");
            LegacyPlayerDataFileReader.Snapshot source;
            CompoundTag owner=null;
            if(singleplayerOwner && loadedPlayer!=null){
                source=LegacyPlayerDataFileReader.readWorldPlayer(world.worldDirectory(),actor);
                require(sameCritical(source.data(),loadedPlayer),"Loaded owner tag and authoritative world player disagree");owner=loadedPlayer;
            }else{
                Path directory=world.worldDirectory().resolve("playerdata"),file=directory.resolve(actor+".dat");
                require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Missing or linked native player directory");
                if(absent(file)){
                    require(!journal.hasPlayerHistory(actor),"Missing player file for a recorded actor");requireAbsentPlayerFiles(world.worldDirectory(),actor);
                    return new Receipt(world,actor,null,null);
                }
                source=LegacyPlayerDataFileReader.read(directory,actor);
            }
            journal.verifyResolvedPlayer(source.data());
            require(journal.proposePersistentMarker(source.data()).equals(source.data()),"Legacy transaction marker needs persistent promotion before admission");
            if(owner!=null)journal.verifyResolvedPlayer(owner);
            return new Receipt(world,actor,source,owner);
        }
    }
    /** Checked during startup before journal decision, including dedicated mode
     * reopening a former integrated world whose loaded Player tag can be saved.
     */
    public static void verifyPendingWorldCache(LegacyQuestWorldStore world,CompoundTag loaded)throws IOException {
        var pending=journal(world).pending();
        if(pending.isPresent() && pending.orElseThrow().authority()==Authority.WORLD_PLAYER){
            var file=LegacyPlayerDataFileReader.readWorldPlayer(world.worldDirectory(),pending.orElseThrow().actor());
            require(loaded!=null && sameCritical(file.data(),loaded),"Pending world player and loaded cache disagree");file.verifyUnchanged();
        }
    }
    public static void verifyResolvedWorldCache(LegacyQuestWorldStore world,CompoundTag loaded)throws IOException {
        if(loaded==null)return;
        require(loaded.hasUUID("UUID"),"Loaded world player UUID missing");
        journal(world).verifyResolvedPlayer(loaded);
        if(journal(world).hasPlayerHistory(loaded.getUUID("UUID"))){
            var file=LegacyPlayerDataFileReader.readWorldPlayer(world.worldDirectory(),loaded.getUUID("UUID"));
            require(sameCritical(file.data(),loaded),"Recorded world player and loaded cache disagree");file.verifyUnchanged();
        }
    }
    private static LegacyRetrievalTransactionJournal journal(LegacyQuestWorldStore world){return new LegacyRetrievalTransactionJournal(world.ledger(),world.binding(),world.catalog());}
    private static boolean sameCritical(CompoundTag a,CompoundTag b)throws IOException {
        return a.hasUUID("UUID") && b.hasUUID("UUID") && a.getUUID("UUID").equals(b.getUUID("UUID"))
            && a.get("Inventory") instanceof ListTag && a.get("Inventory").equals(b.get("Inventory"))
            && LegacyRetrievalTransactionJournal.readMarker(a).equals(LegacyRetrievalTransactionJournal.readMarker(b));
    }
    private static boolean absent(Path path)throws IOException {
        try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false;}
        catch(NoSuchFileException expected){return true;}
    }
    private static void requireAbsentPlayerFiles(Path world,UUID actor)throws IOException {
        Path directory=world.resolve("playerdata");require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Missing or linked player directory");
        require(absent(directory.resolve(actor+".dat")) && absent(directory.resolve(actor+".dat_old")),"New player unexpectedly has primary or fallback data");
    }
    private static void require(boolean condition,String message)throws IOException {if(!condition)throw new IOException(message);}
}

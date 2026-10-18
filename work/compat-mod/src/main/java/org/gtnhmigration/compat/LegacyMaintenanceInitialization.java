package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.State;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.SourceDocument;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.digest;

/** Creates auxiliary journals with durable, world-bound construction evidence.
 * An incomplete proven genesis may be completed; an established journal is never
 * reset. Main quest progress, player/world files and all orphan bytes are retained.
 * Caller holds the world ledger and excludes native saves and external writers.
 */
final class LegacyMaintenanceInitialization {
    enum Kind {
        MARKER("player-marker-upgrades","gtnh-player-marker-upgrade:v1/"),
        OWNER("owner-transaction-recovery","gtnh-owner-transaction-recovery:v1/");
        final String directory,prefix;
        Kind(String directory,String prefix){this.directory=directory;this.prefix=prefix;}
    }
    enum Boundary { INTENT_TEMP_OPENED, INTENT_TEMP_FORCED, INTENT_PUBLISHED, ROOT_CREATED, BLOBS_CREATED, LEDGER_OPENED,
        GENESIS_TEMP_OPENED, GENESIS_TEMP_FORCED, GENESIS_PUBLISHED, COMPLETE_TEMP_OPENED, COMPLETE_TEMP_FORCED, COMPLETE_PUBLISHED }
    @FunctionalInterface interface Observer {void reached(Boundary point)throws IOException;}
    private LegacyMaintenanceInitialization(){}
    static Path root(LegacyQuestWorldStore world,Kind kind){return world.worldDirectory().resolve(LegacyQuestWorldStore.DIRECTORY).resolve(kind.directory);}
    static Path intent(LegacyQuestWorldStore world,Kind kind){return root(world,kind).resolveSibling(kind.directory+".init");}
    static Path complete(LegacyQuestWorldStore world,Kind kind){return root(world,kind).resolveSibling(kind.directory+".ready");}
    static boolean present(LegacyQuestWorldStore world,Kind kind)throws IOException{return exists(root(world,kind)) || exists(intent(world,kind)) || exists(complete(world,kind));}
    static SourceDocument origin(LegacyQuestWorldStore world,Kind kind)throws IOException{return new SourceDocument(kind.prefix+"world/"+world.storageId(),world.ledger().origin().orElseThrow(()->new IOException("Missing world origin")).frame().snapshot().sources().getFirst().bytes());}
    static byte[] intentBytes(LegacyQuestWorldStore world,Kind kind)throws IOException{
        SourceDocument origin=origin(world,kind);
        return ("GTNH-MAINTENANCE-INIT-1\n"+kind.directory+"\n"+origin.identity()+"\n"+origin.sha256()+"\n").getBytes(StandardCharsets.UTF_8);
    }
    static byte[] completeBytes(byte[] intent){return ("GTNH-MAINTENANCE-READY-1\n"+digest(intent)+"\n").getBytes(StandardCharsets.UTF_8);}
    static LegacyQuestProgressFileStore open(LegacyQuestWorldStore world,Kind kind,boolean create)throws IOException{return open(world,kind,create,point->{});}
    static LegacyQuestProgressFileStore open(LegacyQuestWorldStore world,Kind kind,boolean create,Observer observer)throws IOException{
        synchronized(world.ledger()){
            world.verifyBinding();Path root=root(world,kind),blobs=root.resolve("blobs"),ledgerPath=root.resolve("ledger"),intent=intent(world,kind),complete=complete(world,kind);
            byte[] expected=intentBytes(world,kind);boolean hasIntent=exists(intent),hasComplete=exists(complete),hasRoot=exists(root);
            require(!hasComplete || hasIntent,"Maintenance completion has no construction intent");
            // Legacy journals remain reopen-only. Absence of new evidence never authorizes repair.
            boolean legacy=hasRoot && !hasIntent;
            if(!hasIntent && !legacy){require(create,"Maintenance construction intent is missing");
                publish(intent,expected,kind.directory+".initializing-",observer,Boundary.INTENT_TEMP_OPENED,Boundary.INTENT_TEMP_FORCED,Boundary.INTENT_PUBLISHED);hasIntent=true;}
            if(hasIntent)require(Arrays.equals(read(intent),expected),"Maintenance intent belongs to another world/protocol or is corrupt");
            if(hasComplete)require(Arrays.equals(read(complete),completeBytes(expected)),"Maintenance completion does not match intent");
            boolean initializing=hasIntent && !hasComplete;
            if(!initializing){directory(root);directory(blobs);directory(ledgerPath);}
            else {
                if(!hasRoot){Files.createDirectory(root);observer.reached(Boundary.ROOT_CREATED);}else directory(root);
                layout(root);
                if(!exists(blobs)){Files.createDirectory(blobs);observer.reached(Boundary.BLOBS_CREATED);}else directory(blobs);
                try(var entries=Files.newDirectoryStream(blobs)){require(!entries.iterator().hasNext(),"Incomplete initialization already contains archived operations; refusing reset");}
                if(exists(ledgerPath))directory(ledgerPath);
            }
            layout(root);SourceDocument origin=origin(world,kind);
            var ledger=new LegacyQuestProgressFileStore(ledgerPath,world.binding(),world.catalog(),point->{
                observer.reached(switch(point){case TEMP_OPENED->Boundary.GENESIS_TEMP_OPENED;case TEMP_FORCED->Boundary.GENESIS_TEMP_FORCED;case PUBLISHED->Boundary.GENESIS_PUBLISHED;});
            });
            try{
                if(initializing){
                    observer.reached(Boundary.LEDGER_OPENED);
                    if(ledger.current().isEmpty())ledger.commit(Optional.empty(),State.empty(),List.of(origin));
                    var head=ledger.current().orElseThrow();
                    require(head.frame().revision()==1 && head.frame().snapshot().state().equals(State.empty()) && head.frame().snapshot().sources().equals(List.of(origin)),"Incomplete initialization contains established history; refusing reset");
                    world.verifyBinding();require(Arrays.equals(read(intent),expected),"Maintenance construction intent changed");
                    publish(complete,completeBytes(expected),kind.directory+".completing-",observer,Boundary.COMPLETE_TEMP_OPENED,Boundary.COMPLETE_TEMP_FORCED,Boundary.COMPLETE_PUBLISHED);
                }
                var first=ledger.origin().orElseThrow(()->new IOException("Maintenance origin missing; refusing reset"));
                require(first.frame().snapshot().state().equals(State.empty()) && first.frame().snapshot().sources().equals(List.of(origin)),"Maintenance origin belongs to another world/protocol");
                if(hasIntent){require(Arrays.equals(read(intent),expected),"Maintenance intent changed before use");require(Arrays.equals(read(complete),completeBytes(expected)),"Maintenance completion changed before use");}
                else require(!exists(intent) && !exists(complete),"Legacy journal gained construction evidence while opening");
                world.verifyBinding();return ledger;
            }catch(IOException|RuntimeException|Error failure){try{ledger.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
        }
    }
    private static void layout(Path root)throws IOException{try(var entries=Files.newDirectoryStream(root)){for(Path p:entries)require(Set.of("blobs","ledger").contains(p.getFileName().toString()),"Unknown maintenance directory entry");}}
    private static boolean exists(Path path)throws IOException{try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}catch(NoSuchFileException absent){return false;}}
    private static void directory(Path path)throws IOException{require(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),"Missing or linked maintenance directory");}
    private static byte[] read(Path path)throws IOException{
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);require(before.isRegularFile() && before.size()>0 && before.size()<=1024,"Invalid maintenance initialization evidence");byte[] bytes;
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(1025);}
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(after.isRegularFile() && bytes.length==before.size() && before.size()==after.size() && Objects.equals(before.fileKey(),after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime()),"Initialization evidence changed while reading");return bytes;
    }
    private static void publish(Path target,byte[] bytes,String prefix,Observer observer,Boundary opened,Boundary forced,Boundary published)throws IOException{
        Path temporary=target.resolveSibling(prefix+UUID.randomUUID()+".tmp");require(!exists(target),"Refusing to replace initialization evidence");
        try(var file=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            observer.reached(opened);ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);
        }
        observer.reached(forced);require(!exists(target),"Initialization evidence appeared during publication");Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);observer.reached(published);
    }
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}

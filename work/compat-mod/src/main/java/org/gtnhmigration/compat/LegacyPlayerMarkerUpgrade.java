package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Snapshot;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.State;
import org.gtnhmigration.compat.LegacyQuestProgressFileStore.Head;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.SourceDocument;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.digest;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.sameNbt;

/** Durable marker-placement maintenance, separate from quest progress. Raw
 * before/after gzip blobs are immutable; an intent precedes file replacement.
 * Caller excludes native saves, player loads and external writers throughout.
 * No item count, task progress, world metadata or foreign mod data is changed.
 */
public final class LegacyPlayerMarkerUpgrade {
    public static final String DIRECTORY="player-marker-upgrades";
    private static final String PREFIX="gtnh-player-marker-upgrade:v1/";
    enum Boundary { BLOBS_ARCHIVED, PREPARED, PLAYER_PUBLISHED, WORLD_PUBLISHED, CACHE_UPDATED, RESOLVED }
    @FunctionalInterface interface Observer {void reached(Boundary point)throws IOException;}
    private record Change(Authority authority,Snapshot before,Snapshot after) {}
    private record Pending(UUID id,UUID actor,long revision,String head,List<Change> files,boolean mirrorAbsent,SourceDocument source) {}
    private LegacyPlayerMarkerUpgrade(){}

    /** Resume a prior intent before permitting native world/player loading.
     * Null cache supplier means no loaded world exists (offline operation).
     */
    public static boolean resume(LegacyQuestWorldStore world,Supplier<CompoundTag> cache,Runnable barrier)throws IOException {
        return resume(world,cache,barrier,point->{});
    }
    static boolean resume(LegacyQuestWorldStore world,Supplier<CompoundTag> cache,Runnable barrier,Observer observer)throws IOException {
        synchronized(world.ledger()){
            barrier.run();world.verifyBinding();if(!exists(root(world)))return false;
            try(var journal=new Maintenance(world,false)){
                Optional<Pending> pending=journal.pending();if(pending.isEmpty())return false;
                execute(journal,pending.orElseThrow(),cache,barrier,observer);return true;
            }
        }
    }
    /** Validates the native authority selected by the caller. Missing UUID data
     * is left for the first-login admission check; missing world data is an error.
     * A WORLD_PLAYER operation includes a present UUID mirror as a witness.
     */
    public static boolean promote(LegacyQuestWorldStore world,UUID actor,Authority authority,Supplier<CompoundTag> cache,Runnable barrier)throws IOException {
        return promote(world,actor,authority,cache,barrier,point->{});
    }
    static boolean promote(LegacyQuestWorldStore world,UUID actor,Authority authority,Supplier<CompoundTag> cache,Runnable barrier,Observer observer)throws IOException {
        synchronized(world.ledger()){
            barrier.run();world.verifyBinding();
            if(exists(root(world))){try(var previous=new Maintenance(world,false)){
                var pending=previous.pending();if(pending.isPresent()){
                    require(pending.orElseThrow().actor().equals(actor) && pending.orElseThrow().files().getFirst().authority()==authority,"Another marker upgrade requires startup recovery");
                    execute(previous,pending.orElseThrow(),cache,barrier,observer);return true;
                }
            }}
            var progress=progress(world);require(progress.pending().isEmpty(),"Resolve inventory transaction before marker upgrade");
            Head head=world.ledger().current().orElseThrow();
            if(authority==Authority.PLAYER_FILE && !exists(path(world,actor,authority)))return false;
            Snapshot primary=read(world,actor,authority);progress.verifyResolvedPlayer(primary.data());
            // No marker means there is no placement to migrate.
            if(LegacyRetrievalTransactionJournal.readMarker(primary.data()).isEmpty())return false;
            if(authority==Authority.WORLD_PLAYER)checkCache(primary.data(),cache);
            List<Change> changes=new ArrayList<>();changes.add(change(primary));boolean mirrorAbsent=false;
            if(authority==Authority.WORLD_PLAYER){
                mirrorAbsent=!exists(path(world,actor,Authority.PLAYER_FILE));
                if(!mirrorAbsent){Snapshot mirror=read(world,actor,Authority.PLAYER_FILE);progress.verifyResolvedPlayer(mirror.data());changes.add(change(mirror));}
            }
            for(Change c:changes)require(sameCritical(primary.data(),c.before().data()),"Owner mirror has different inventory or transaction identity");
            boolean changed=changes.stream().anyMatch(c->!c.before().sha256().equals(c.after().sha256()));
            if(!changed){
                barrier.run();world.verifyBinding();require(world.ledger().current().orElseThrow().equals(head),"Progress changed during marker check");
                for(Change c:changes)c.before().verifyUnchanged();if(mirrorAbsent)require(!exists(path(world,actor,Authority.PLAYER_FILE)),"Owner mirror appeared during cache marker check");
                if(authority==Authority.WORLD_PLAYER)promoteCache(primary.data(),cache);
                barrier.run();world.verifyBinding();require(world.ledger().current().orElseThrow().equals(head),"Progress changed during cache marker promotion");
                for(Change c:changes)c.before().verifyUnchanged();if(mirrorAbsent)require(!exists(path(world,actor,Authority.PLAYER_FILE)),"Owner mirror appeared during cache marker check");
                if(authority==Authority.WORLD_PLAYER)requirePromotedCache(primary.data(),cache);
                return false;
            }
            validatePair(changes);barrier.run();world.verifyBinding();require(world.ledger().current().orElseThrow().equals(head),"Progress changed before marker intent");
            for(Change c:changes)c.before().verifyUnchanged();if(mirrorAbsent)require(!exists(path(world,actor,Authority.PLAYER_FILE)),"Owner mirror appeared during preparation");
            try(var journal=new Maintenance(world,true)){
                require(journal.pending().isEmpty(),"Marker journal became busy");
                for(Change c:changes){journal.archive(c.before().originalBytes());journal.archive(c.after().originalBytes());}
                observer.reached(Boundary.BLOBS_ARCHIVED);
                Pending pending=journal.prepare(actor,head,changes,mirrorAbsent);observer.reached(Boundary.PREPARED);
                execute(journal,pending,cache,barrier,observer);return true;
            }
        }
    }
    private static void execute(Maintenance journal,Pending pending,Supplier<CompoundTag> cache,Runnable barrier,Observer observer)throws IOException {
        checkPending(journal,pending,cache,barrier);
        // UUID mirror first; the authoritative world file is published last.
        List<Change> order=pending.files().stream().sorted(Comparator.comparing(Change::authority)).toList();
        for(Change change:order){
            checkPending(journal,pending,cache,barrier);
            Snapshot current=read(journal.world,pending.actor(),change.authority());
            if(current.sha256().equals(change.after().sha256()))continue;
            require(current.sha256().equals(change.before().sha256()),"Player file is neither upgrade before-state nor after-state");
            Path temporary=current.path().resolveSibling("gtnh-marker-"+UUID.randomUUID()+".tmp");
            writeForced(temporary,change.after().originalBytes());
            checkPending(journal,pending,cache,barrier);current.verifyUnchanged();
            Files.move(temporary,current.path(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            observer.reached(change.authority()==Authority.PLAYER_FILE?Boundary.PLAYER_PUBLISHED:Boundary.WORLD_PUBLISHED);
            require(read(journal.world,pending.actor(),change.authority()).sha256().equals(change.after().sha256()),"Published player marker file did not read back exactly");
        }
        checkPending(journal,pending,cache,barrier);
        if(pending.files().getFirst().authority()==Authority.WORLD_PLAYER)promoteCache(pending.files().getFirst().after().data(),cache);
        observer.reached(Boundary.CACHE_UPDATED);
        checkPending(journal,pending,cache,barrier);
        for(Change c:pending.files())require(read(journal.world,pending.actor(),c.authority()).sha256().equals(c.after().sha256()),"Marker files changed before resolution");
        if(pending.files().getFirst().authority()==Authority.WORLD_PLAYER)requirePromotedCache(pending.files().getFirst().after().data(),cache);
        journal.resolve(pending);observer.reached(Boundary.RESOLVED);
    }
    private static void checkPending(Maintenance journal,Pending pending,Supplier<CompoundTag> cache,Runnable barrier)throws IOException {
        barrier.run();journal.world.verifyBinding();var head=journal.world.ledger().current().orElseThrow();
        require(head.frame().revision()==pending.revision() && head.sha256().equals(pending.head()),"Quest ledger changed during pending marker maintenance");
        require(progress(journal.world).pending().isEmpty(),"Inventory preparation overlaps marker upgrade");
        var actual=journal.pending().orElseThrow(()->new IOException("Marker intent disappeared"));require(actual.source().equals(pending.source()),"Marker intent changed");
        for(Change c:pending.files()){
            progress(journal.world).verifyResolvedPlayer(c.before().data());
            Snapshot current=read(journal.world,pending.actor(),c.authority());
            require(current.sha256().equals(c.before().sha256()) || current.sha256().equals(c.after().sha256()),"Unknown file state during marker maintenance");
        }
        if(pending.mirrorAbsent())require(!exists(path(journal.world,pending.actor(),Authority.PLAYER_FILE)),"Owner mirror appeared during marker upgrade");
        if(pending.files().getFirst().authority()==Authority.WORLD_PLAYER)checkCache(pending.files().getFirst().before().data(),cache);
    }
    private static Change change(Snapshot before)throws IOException {
        CompoundTag player=LegacyRetrievalTransactionJournal.persistentMarkerCandidate(before.data());
        if(sameNbt(player,before.data()))return new Change(before.authority(),before,before);
        CompoundTag document=before.documentData();
        if(before.authority()==Authority.PLAYER_FILE)document=player;else document.getCompound("Data").put("Player",player);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(RawMarkerNbt.promote(before));}
        Snapshot after=LegacyPlayerDataFileReader.decodeArchived(before.path().getParent(),before.actor(),before.authority(),bytes.toByteArray());
        require(sameNbt(document,after.documentData()),"Marker candidate did not survive NBT/gzip round trip");return new Change(before.authority(),before,after);
    }
    private static void validatePair(List<Change> files)throws IOException {
        require(files.size()>=1 && files.size()<=2,"Invalid marker file set");
        require(files.size()==1 || files.getFirst().authority()==Authority.WORLD_PLAYER && files.get(1).authority()==Authority.PLAYER_FILE,"Invalid owner mirror order");
        boolean changed=false;
        for(Change c:files){
            CompoundTag expected=LegacyRetrievalTransactionJournal.persistentMarkerCandidate(c.before().data());CompoundTag document=c.before().documentData();
            if(c.authority()==Authority.PLAYER_FILE)document=expected;else document.getCompound("Data").put("Player",expected);
            require(sameNbt(document,c.after().documentData()),"Marker upgrade modifies unrelated player/world NBT");
            require(LegacyRetrievalTransactionJournal.readMarker(c.before().data()).isPresent(),"Marker upgrade lacks a known marker");
            if(!sameNbt(c.before().data(),expected)){
                require(Arrays.equals(RawMarkerNbt.promote(c.before()),LegacyPlayerDataFileReader.inflateSingleMember(c.after().originalBytes())),"Marker upgrade changes unrelated binary NBT");changed=true;
            }
            else require(c.before().sha256().equals(c.after().sha256()),"Unchanged marker witness was reencoded");
            require(sameCritical(files.getFirst().before().data(),c.before().data()),"Owner mirror has different inventory or transaction identity");
        }
        require(changed,"Marker intent contains no legacy placement change");
    }
    private static void checkCache(CompoundTag expected,Supplier<CompoundTag> cache)throws IOException {
        if(cache==null)return;CompoundTag value=cache.get();
        require(value!=null && sameCritical(expected,value),"Loaded owner cache disagrees with marker upgrade authority");
    }
    private static void promoteCache(CompoundTag expected,Supplier<CompoundTag> cache)throws IOException {
        if(cache==null)return;checkCache(expected,cache);CompoundTag value=cache.get();
        require(value!=null && sameCritical(expected,value),"Owner cache changed during promotion");
        CompoundTag candidate=LegacyRetrievalTransactionJournal.persistentMarkerCandidate(value);
        if(!sameNbt(candidate,value))value.put("NeoForgeData",candidate.getCompound("NeoForgeData").copy());
        CompoundTag actual=cache.get();require(actual!=null && sameCritical(expected,actual)
            && sameNbt(LegacyRetrievalTransactionJournal.persistentMarkerCandidate(actual),actual),"Owner marker promotion was not retained");
    }
    private static void requirePromotedCache(CompoundTag expected,Supplier<CompoundTag> cache)throws IOException {
        if(cache==null)return;CompoundTag actual=cache.get();require(actual!=null && sameCritical(expected,actual)
            && sameNbt(LegacyRetrievalTransactionJournal.persistentMarkerCandidate(actual),actual),"Owner cache marker changed before maintenance resolution");
    }
    private static boolean sameCritical(CompoundTag a,CompoundTag b)throws IOException {
        return a.hasUUID("UUID") && b.hasUUID("UUID") && a.getUUID("UUID").equals(b.getUUID("UUID"))
            && a.get("Inventory") instanceof ListTag && sameNbt(a.get("Inventory"),b.get("Inventory"))
            && LegacyRetrievalTransactionJournal.readMarker(a).equals(LegacyRetrievalTransactionJournal.readMarker(b));
    }
    private static LegacyRetrievalTransactionJournal progress(LegacyQuestWorldStore world){return new LegacyRetrievalTransactionJournal(world.ledger(),world.binding(),world.catalog());}
    private static Path root(LegacyQuestWorldStore world){return world.worldDirectory().resolve(LegacyQuestWorldStore.DIRECTORY).resolve(DIRECTORY);}
    private static Path path(LegacyQuestWorldStore world,UUID actor,Authority authority){return authority==Authority.WORLD_PLAYER?world.worldDirectory().resolve("level.dat"):world.worldDirectory().resolve("playerdata").resolve(actor+".dat");}
    private static Snapshot read(LegacyQuestWorldStore world,UUID actor,Authority authority)throws IOException{return authority==Authority.WORLD_PLAYER?LegacyPlayerDataFileReader.readWorldPlayer(world.worldDirectory(),actor):LegacyPlayerDataFileReader.read(world.worldDirectory().resolve("playerdata"),actor);}
    private static boolean exists(Path path)throws IOException {try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}catch(NoSuchFileException absent){return false;}}
    private static void directory(Path path)throws IOException{require(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),"Missing or linked marker-maintenance directory");}
    private static void writeForced(Path path,byte[] bytes)throws IOException {
        try(FileChannel output=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
    }

    /** Edits named-field spans instead of serializing decoded foreign data.
     * Native NBT normalizes negative zero on read and NaN payloads on write.
     * All bytes outside the marker placement, including the root name and
     * unknown fields, retain their original representation and ordering.
     */
    private static final class RawMarkerNbt {
        private record Field(int type,String name,int start,int payload,int end) {}
        final byte[] raw;
        RawMarkerNbt(byte[] raw){this.raw=raw;}
        static byte[] promote(Snapshot snapshot)throws IOException {
            RawMarkerNbt nbt=new RawMarkerNbt(LegacyPlayerDataFileReader.inflateSingleMember(snapshot.originalBytes()));
            Field root=nbt.new Cursor(0).field(0);require(root.type()==10 && root.end()==nbt.raw.length,"Invalid raw marker document");
            Field player=root;
            if(snapshot.authority()==Authority.WORLD_PLAYER)player=nbt.required(nbt.required(root,"Data"),"Player");
            Field forge=nbt.required(player,"NeoForgeData");List<Field> fields=nbt.fields(forge);
            Field marker=nbt.find(fields,LegacyRetrievalTransactionJournal.MARKER_KEY);
            require(marker!=null && marker.type()==10,"Missing raw legacy marker");
            require(nbt.fields(marker).size()==3,"Duplicate raw marker fields");
            nbt.required(marker,"transaction");nbt.required(marker,"preparation_sha256");
            Field version=nbt.required(marker,"schema_version");require(version.type()==3 && version.end()-version.payload()==4,"Invalid raw marker version");
            require(ByteBuffer.wrap(nbt.raw,version.payload(),4).getInt()==1,"Raw marker is not v1");
            byte[] modern=Arrays.copyOfRange(nbt.raw,marker.start(),marker.end());ByteBuffer.wrap(modern,version.payload()-marker.start(),4).putInt(2);
            Field persisted=nbt.find(fields,LegacyRetrievalTransactionJournal.PERSISTED_KEY);
            byte[] replacement;
            if(persisted==null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF(LegacyRetrievalTransactionJournal.PERSISTED_KEY);out.write(modern);out.writeByte(0);replacement=bytes.toByteArray();}
            else{
                require(persisted.type()==10 && nbt.find(nbt.fields(persisted),LegacyRetrievalTransactionJournal.MARKER_KEY)==null,"Ambiguous raw persisted marker");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(nbt.raw,persisted.start(),persisted.end()-persisted.start()-1);bytes.write(modern);bytes.write(0);replacement=bytes.toByteArray();
            }
            ByteArrayOutputStream value=new ByteArrayOutputStream();value.write(nbt.raw,forge.start(),forge.payload()-forge.start());
            for(Field field:fields){if(field==marker)continue;if(field==persisted)value.write(replacement);else value.write(nbt.raw,field.start(),field.end()-field.start());}
            if(persisted==null)value.write(replacement);value.write(0);
            ByteArrayOutputStream document=new ByteArrayOutputStream();document.write(nbt.raw,0,forge.start());value.writeTo(document);document.write(nbt.raw,forge.end(),nbt.raw.length-forge.end());return document.toByteArray();
        }
        Field required(Field parent,String name)throws IOException{Field result=find(fields(parent),name);require(result!=null,"Missing raw NBT field: "+name);return result;}
        Field find(List<Field> fields,String name)throws IOException{Field result=null;for(Field field:fields)if(field.name().equals(name)){require(result==null,"Duplicate raw marker-path field: "+name);result=field;}return result;}
        List<Field> fields(Field compound)throws IOException{
            require(compound.type()==10,"Raw marker path is not a compound");Cursor cursor=new Cursor(compound.payload());List<Field> result=new ArrayList<>();
            while(cursor.pos()<compound.end()-1)result.add(cursor.field(0));require(cursor.pos()==compound.end()-1 && cursor.input.readUnsignedByte()==0,"Malformed raw compound");return result;
        }
        private final class Cursor {
            final ByteArrayInputStream bytes;final DataInputStream input;
            Cursor(int offset){bytes=new ByteArrayInputStream(raw,offset,raw.length-offset);input=new DataInputStream(bytes);}
            int pos(){return raw.length-bytes.available();}
            Field field(int depth)throws IOException{int start=pos(),type=input.readUnsignedByte();require(type>=1 && type<=12,"Invalid raw named tag type");String name=input.readUTF();int payload=pos();skip(type,depth);return new Field(type,name,start,payload,pos());}
            void take(long count)throws IOException{require(count>=0 && count<=bytes.available(),"Truncated raw NBT");input.skipNBytes(count);}
            void array(int width)throws IOException{int count=input.readInt();require(count>=0,"Negative raw array length");take((long)count*width);}
            void skip(int type,int depth)throws IOException{
                require(depth<=512,"Raw NBT nesting limit exceeded");
                switch(type){
                    case 1->take(1);case 2->take(2);case 3,5->take(4);case 4,6->take(8);
                    case 7->array(1);case 8->take(input.readUnsignedShort());
                    case 9->{int child=input.readUnsignedByte(),count=input.readInt();require(count>=0 && child<=12 && (child!=0 || count==0) && count<=bytes.available(),"Invalid raw list");for(int i=0;i<count;i++)skip(child,depth+1);}
                    case 10->{while(true){int child=input.readUnsignedByte();if(child==0)break;require(child<=12,"Unknown raw tag type");take(input.readUnsignedShort());skip(child,depth+1);}}
                    case 11->array(4);case 12->array(8);default->throw new IOException("Unknown raw NBT payload");
                }
            }
        }
    }

    private static final class Maintenance implements AutoCloseable {
        final LegacyQuestWorldStore world;final Path root,blobs;final LegacyQuestProgressFileStore ledger;final SourceDocument origin;
        Maintenance(LegacyQuestWorldStore world,boolean create)throws IOException {
            this.world=world;root=root(world);blobs=root.resolve("blobs");
            origin=new SourceDocument(PREFIX+"world/"+world.storageId(),world.ledger().origin().orElseThrow().frame().snapshot().sources().getFirst().bytes());
            boolean fresh=!exists(root);require(!fresh || create,"Marker maintenance directory is missing");
            if(fresh){Files.createDirectory(root);Files.createDirectory(blobs);}else{directory(root);directory(blobs);directory(root.resolve("ledger"));}
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){for(Path entry:entries)require(Set.of("blobs","ledger").contains(entry.getFileName().toString()),"Unknown marker-maintenance entry");}
            ledger=LegacyQuestProgressFileStore.open(root.resolve("ledger"),world.binding(),world.catalog());
            try{
                if(fresh)ledger.commit(Optional.empty(),State.empty(),List.of(origin));
                var first=ledger.origin().orElseThrow(()->new IOException("Marker maintenance origin is missing; refusing reset"));
                require(first.frame().snapshot().sources().equals(List.of(origin)),"Marker archive belongs to another world");pending();
            }catch(IOException|RuntimeException|Error failure){try{ledger.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
        }
        byte[] blob(String hash)throws IOException {
            digestField(hash);Path path=blobs.resolve(hash+".gz");
            var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);require(attrs.isRegularFile() && attrs.size()>0 && attrs.size()<=LegacyPlayerDataFileReader.MAX_COMPRESSED_BYTES,"Invalid archived marker blob");byte[] bytes;
            try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(LegacyPlayerDataFileReader.MAX_COMPRESSED_BYTES+1);}
            require(bytes.length==attrs.size() && digest(bytes).equals(hash),"Archived marker blob is corrupt");return bytes;
        }
        void archive(byte[] bytes)throws IOException {
            String hash=digest(bytes);Path target=blobs.resolve(hash+".gz");
            if(exists(target)){require(Arrays.equals(blob(hash),bytes),"Existing marker archive differs");return;}
            Path temporary=blobs.resolve("pending-"+UUID.randomUUID()+".tmp");writeForced(temporary,bytes);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);blob(hash);
        }
        Optional<Pending> pending()throws IOException {
            directory(root);directory(blobs);
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(blobs)){for(Path entry:entries){String name=entry.getFileName().toString();require(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS) && (name.matches("[0-9a-f]{64}\\.gz") || name.matches("pending-[0-9a-f-]{36}\\.tmp")),"Unknown marker blob entry");}}
            Head head=ledger.current().orElseThrow(()->new IOException("Empty marker maintenance ledger"));var sources=head.frame().snapshot().sources();
            require(head.frame().snapshot().state().equals(State.empty()) && head.frame().revision()==sources.size() && !sources.isEmpty() && sources.getFirst().equals(origin),"Invalid marker maintenance chain");
            Pending pending=null;Set<UUID> seen=new HashSet<>();
            for(int i=1;i<sources.size();i++){
                SourceDocument source=sources.get(i);CompoundTag tag=decode(source.bytes());String kind=string(tag,"kind");
                require(tag.get("schema_version") instanceof IntTag && tag.getInt("schema_version")==1,"Unknown marker maintenance schema");
                UUID id=uuid(string(tag,"operation"));require(source.identity().equals(PREFIX+id+"/"+kind),"Marker source identity mismatch");
                if(kind.equals("prepare")){
                    require(pending==null && seen.add(id),"Overlapping or repeated marker upgrade");pending=parse(source,tag,id);
                }else if(kind.equals("resolve")){
                    fields(tag,"schema_version","kind","operation","preparation_sha256");require(pending!=null && pending.id().equals(id) && string(tag,"preparation_sha256").equals(pending.source().sha256()),"Marker resolution does not match intent");pending=null;
                }else throw new IOException("Unknown marker maintenance record");
            }
            return Optional.ofNullable(pending);
        }
        Pending parse(SourceDocument source,CompoundTag tag,UUID id)throws IOException {
            fields(tag,"schema_version","kind","operation","actor","base_revision","base_head","mirror_absent","files");
            UUID actor=uuid(string(tag,"actor"));require(tag.get("base_revision") instanceof LongTag && tag.getLong("base_revision")>0,"Invalid marker base revision");long revision=tag.getLong("base_revision");String head=string(tag,"base_head");digestField(head);
            require(Files.isRegularFile(world.ledger().directory().resolve(String.format(Locale.ROOT,"%020d-%s.nbt",revision,head)),LinkOption.NOFOLLOW_LINKS),"Marker archive references missing quest history");
            require(tag.get("mirror_absent") instanceof ByteTag && (tag.getByte("mirror_absent")==0 || tag.getByte("mirror_absent")==1),"Invalid mirror absence witness");boolean absent=tag.getBoolean("mirror_absent");
            require(tag.get("files") instanceof ListTag,"Missing marker file list");ListTag rows=(ListTag)tag.get("files");require(!rows.isEmpty() && rows.size()<=2 && rows.getElementType()==Tag.TAG_COMPOUND,"Invalid marker file rows");
            List<Change> files=new ArrayList<>();
            for(Tag value:rows){CompoundTag row=(CompoundTag)value;fields(row,"authority","before","after");Authority authority;
                try{authority=Authority.valueOf(string(row,"authority"));}catch(IllegalArgumentException e){throw new IOException("Unknown marker file authority",e);}
                Path directory=path(world,actor,authority).getParent();
                files.add(new Change(authority,LegacyPlayerDataFileReader.decodeArchived(directory,actor,authority,blob(string(row,"before"))),LegacyPlayerDataFileReader.decodeArchived(directory,actor,authority,blob(string(row,"after")))));
            }
            require(absent==(files.getFirst().authority()==Authority.WORLD_PLAYER && files.size()==1),"Marker mirror witness does not match file set");validatePair(files);
            return new Pending(id,actor,revision,head,List.copyOf(files),absent,source);
        }
        Pending prepare(UUID actor,Head base,List<Change> files,boolean mirrorAbsent)throws IOException {
            UUID id=UUID.randomUUID();CompoundTag tag=record("prepare",id);tag.putString("actor",actor.toString());tag.putLong("base_revision",base.frame().revision());tag.putString("base_head",base.sha256());tag.putBoolean("mirror_absent",mirrorAbsent);
            ListTag rows=new ListTag();for(Change c:files){CompoundTag row=new CompoundTag();row.putString("authority",c.authority().name());row.putString("before",c.before().sha256());row.putString("after",c.after().sha256());rows.add(row);}tag.put("files",rows);
            SourceDocument source=new SourceDocument(PREFIX+id+"/prepare",encode(tag));Pending parsed=parse(source,tag,id);append(source);return parsed;
        }
        void resolve(Pending pending)throws IOException {CompoundTag tag=record("resolve",pending.id());tag.putString("preparation_sha256",pending.source().sha256());append(new SourceDocument(PREFIX+pending.id()+"/resolve",encode(tag)));}
        void append(SourceDocument source)throws IOException {var head=ledger.current().orElseThrow();var sources=new ArrayList<>(head.frame().snapshot().sources());sources.add(source);ledger.commit(Optional.of(head.sha256()),State.empty(),sources);}
        @Override public void close()throws IOException{ledger.close();}
    }
    private static CompoundTag record(String kind,UUID id){CompoundTag tag=new CompoundTag();tag.putInt("schema_version",1);tag.putString("kind",kind);tag.putString("operation",id.toString());return tag;}
    private static byte[] encode(CompoundTag tag)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(bytes));return bytes.toByteArray();}
    private static CompoundTag decode(byte[] bytes)throws IOException{try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){CompoundTag tag=NbtIo.read(input,NbtAccounter.create(16L*1024*1024));require(tag!=null && input.read()==-1,"Malformed marker maintenance record");return tag;}catch(RuntimeException e){throw new IOException("Invalid marker maintenance NBT",e);}}
    private static String string(CompoundTag tag,String key)throws IOException{require(tag.get(key) instanceof StringTag,"Expected marker string: "+key);return tag.getString(key);}
    private static UUID uuid(String value)throws IOException{try{UUID id=UUID.fromString(value);require(id.toString().equals(value),"Noncanonical marker operation UUID");return id;}catch(IllegalArgumentException e){throw new IOException("Invalid marker operation UUID",e);}}
    private static void digestField(String value)throws IOException{require(value.matches("[0-9a-f]{64}"),"Invalid marker archive digest");}
    private static void fields(CompoundTag tag,String... fields)throws IOException{require(tag.getAllKeys().equals(Set.of(fields)),"Unknown marker maintenance fields");}
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}

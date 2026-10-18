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
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Pending;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Decision;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.digest;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.sameNbt;

/** Startup-only reconciliation of a pending WORLD_PLAYER transaction.
 * level.dat decides the transaction; only proven old/new mirrors and caches can
 * be reconciled. Preserve complete originals, foreign fields and world bytes.
 * Caller excludes saves, loads, live players and all external file writers.
 */
public final class LegacyOwnerTransactionRecovery {
    public static final String DIRECTORY="owner-transaction-recovery";
    private static final String PREFIX="gtnh-owner-transaction-recovery:v1/";
    enum Boundary { BLOBS_ARCHIVED, PREPARED, MIRROR_PUBLISHED, CACHE_UPDATED, MAIN_RESOLVED, RESOLVED }
    @FunctionalInterface interface Observer {void reached(Boundary point)throws IOException;}
    private record Plan(UUID id,long revision,String head,Pending transaction,Decision decision,Snapshot world,Snapshot before,Snapshot after,SourceDocument source) {}
    private LegacyOwnerTransactionRecovery(){}
    static void requireResolved(LegacyQuestWorldStore world)throws IOException {
        synchronized(world.ledger()){if(exists(root(world)))try(var maintenance=new Maintenance(world,false)){require(maintenance.pending().isEmpty(),"Marker migration cannot overlap pending owner recovery");}}
    }
    public static boolean recover(LegacyQuestWorldStore world,Supplier<CompoundTag> cache,Runnable barrier)throws IOException{return recover(world,cache,barrier,point->{});}
    static boolean recover(LegacyQuestWorldStore world,Supplier<CompoundTag> cache,Runnable barrier,Observer observer)throws IOException{
        synchronized(world.ledger()){
            barrier.run();world.verifyBinding();LegacyPlayerMarkerUpgrade.requireResolved(world);
            if(exists(root(world)))try(var maintenance=new Maintenance(world,false)){
                var pending=maintenance.pending();if(pending.isPresent()){execute(maintenance,pending.orElseThrow(),cache,barrier,observer);return true;}
            }
            var progress=progress(world);var pending=progress.pending();
            if(pending.isEmpty() || pending.orElseThrow().authority()!=Authority.WORLD_PLAYER)return false;
            Pending transaction=pending.orElseThrow();Head head=world.ledger().current().orElseThrow();
            Snapshot primary=LegacyPlayerDataFileReader.readWorldPlayer(world.worldDirectory(),transaction.actor());
            Snapshot mirror=LegacyPlayerDataFileReader.read(world.worldDirectory().resolve("playerdata"),transaction.actor());
            transaction.decisionFor(primary.data());transaction.decisionFor(mirror.data());checkKnownCache(transaction,cache);
            Snapshot target=candidate(mirror,primary);
            barrier.run();world.verifyBinding();require(world.ledger().current().orElseThrow().equals(head),"Main ledger changed before recovery intent");primary.verifyUnchanged();mirror.verifyUnchanged();checkKnownCache(transaction,cache);
            try(var maintenance=new Maintenance(world,true)){
                require(maintenance.pending().isEmpty(),"Owner recovery became busy");
                maintenance.archive(primary.originalBytes());maintenance.archive(mirror.originalBytes());maintenance.archive(target.originalBytes());observer.reached(Boundary.BLOBS_ARCHIVED);
                Plan plan=maintenance.prepare(transaction,head,primary,mirror,target);observer.reached(Boundary.PREPARED);execute(maintenance,plan,cache,barrier,observer);return true;
            }
        }
    }
    private static Snapshot candidate(Snapshot mirror,Snapshot primary)throws IOException{
        CompoundTag expected=LegacyPlayerNbtPatch.restoreCache(mirror.data(),primary.data());if(sameNbt(expected,mirror.data()))return mirror;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(LegacyPlayerNbtPatch.restoreMirror(mirror,primary));}
        Snapshot result=LegacyPlayerDataFileReader.decodeArchived(mirror.path().getParent(),mirror.actor(),Authority.PLAYER_FILE,bytes.toByteArray());
        require(sameNbt(expected,result.data()),"Owner mirror repair changed unrelated decoded data");return result;
    }
    private static void execute(Maintenance maintenance,Plan plan,Supplier<CompoundTag> cache,Runnable barrier,Observer observer)throws IOException{
        check(maintenance,plan,cache,barrier);
        Snapshot current=LegacyPlayerDataFileReader.read(plan.before().path().getParent(),plan.transaction().actor());
        if(!current.sha256().equals(plan.after().sha256())){
            Path temporary=current.path().resolveSibling("gtnh-owner-recovery-"+UUID.randomUUID()+".tmp");writeForced(temporary,plan.after().originalBytes());check(maintenance,plan,cache,barrier);current.verifyUnchanged();
            Files.move(temporary,current.path(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);observer.reached(Boundary.MIRROR_PUBLISHED);
        }
        check(maintenance,plan,cache,barrier);require(LegacyPlayerDataFileReader.read(plan.after().path().getParent(),plan.transaction().actor()).sha256().equals(plan.after().sha256()),"Owner mirror did not read back exactly");
        restoreCache(plan,cache);observer.reached(Boundary.CACHE_UPDATED);
        boolean resolved=check(maintenance,plan,cache,barrier);requireTargetCache(plan,cache);plan.after().verifyUnchanged();plan.world().verifyUnchanged();
        if(!resolved){var resolution=progress(maintenance.world).resolve(plan.world());require(resolution.decision()==plan.decision(),"Recovery published another decision");}
        observer.reached(Boundary.MAIN_RESOLVED);
        require(check(maintenance,plan,cache,barrier),"Main transaction is not resolved");requireTargetCache(plan,cache);plan.after().verifyUnchanged();plan.world().verifyUnchanged();
        maintenance.resolve(plan);observer.reached(Boundary.RESOLVED);
    }
    private static boolean check(Maintenance maintenance,Plan plan,Supplier<CompoundTag> cache,Runnable barrier)throws IOException{
        barrier.run();maintenance.world.verifyBinding();require(maintenance.pending().orElseThrow(()->new IOException("Owner recovery intent disappeared")).source().equals(plan.source()),"Owner recovery intent changed");
        var progress=progress(maintenance.world);var head=maintenance.world.ledger().current().orElseThrow();boolean resolved;
        if(head.sha256().equals(plan.head())){var pending=progress.pending().orElseThrow(()->new IOException("Recovery preparation disappeared"));require(pending.transaction().equals(plan.id()),"Another main transaction overlaps recovery");resolved=false;}
        else{var resolution=progress.historicalResolution(plan.transaction(),plan.head()).orElseThrow(()->new IOException("Unexpected main history during recovery"));require(head.sha256().equals(resolution.ledgerSha256()) && resolution.decision()==plan.decision() && progress.pending().isEmpty(),"Main history advanced or selected another recovery outcome");resolved=true;}
        plan.world().verifyUnchanged();Snapshot mirror=LegacyPlayerDataFileReader.read(plan.before().path().getParent(),plan.transaction().actor());
        require(mirror.sha256().equals(plan.before().sha256()) || mirror.sha256().equals(plan.after().sha256()),"Unknown owner mirror during recovery");checkKnownCache(plan.transaction(),cache);return resolved;
    }
    private static void checkKnownCache(Pending transaction,Supplier<CompoundTag> cache)throws IOException{
        if(cache==null)return;CompoundTag value=cache.get();require(value!=null,"Loaded owner cache is missing");transaction.decisionFor(value);
    }
    private static void restoreCache(Plan plan,Supplier<CompoundTag> cache)throws IOException{
        if(cache==null)return;checkKnownCache(plan.transaction(),cache);CompoundTag current=cache.get();require(current!=null,"Owner cache disappeared");plan.transaction().decisionFor(current);
        CompoundTag candidate=LegacyPlayerNbtPatch.restoreCache(current,plan.world().data());current.put("Inventory",candidate.get("Inventory").copy());
        if(candidate.contains("NeoForgeData"))current.put("NeoForgeData",candidate.getCompound("NeoForgeData").copy());requireTargetCache(plan,cache);
    }
    private static void requireTargetCache(Plan plan,Supplier<CompoundTag> cache)throws IOException{
        if(cache==null)return;CompoundTag current=cache.get();require(current!=null && sameNbt(current,LegacyPlayerNbtPatch.restoreCache(current,plan.world().data())),"Loaded owner cache does not retain chosen transaction outcome");
    }
    private static LegacyRetrievalTransactionJournal progress(LegacyQuestWorldStore world){return new LegacyRetrievalTransactionJournal(world.ledger(),world.binding(),world.catalog());}
    private static Path root(LegacyQuestWorldStore world){return world.worldDirectory().resolve(LegacyQuestWorldStore.DIRECTORY).resolve(DIRECTORY);}
    private static boolean exists(Path path)throws IOException{try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}catch(NoSuchFileException absent){return false;}}
    private static void directory(Path path)throws IOException{require(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),"Missing or linked owner-recovery directory");}
    private static void writeForced(Path path,byte[] bytes)throws IOException{try(FileChannel out=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}}
    private static final class Maintenance implements AutoCloseable {
        final LegacyQuestWorldStore world;final Path root,blobs;final LegacyQuestProgressFileStore ledger;final SourceDocument origin;
        Maintenance(LegacyQuestWorldStore world,boolean create)throws IOException{
            this.world=world;root=root(world);blobs=root.resolve("blobs");origin=new SourceDocument(PREFIX+"world/"+world.storageId(),world.ledger().origin().orElseThrow().frame().snapshot().sources().getFirst().bytes());
            boolean fresh=!exists(root);require(!fresh || create,"Owner recovery directory is missing");
            if(fresh){Files.createDirectory(root);Files.createDirectory(blobs);}else{directory(root);directory(blobs);directory(root.resolve("ledger"));}
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){for(Path entry:entries)require(Set.of("blobs","ledger").contains(entry.getFileName().toString()),"Unknown owner recovery entry");}
            ledger=LegacyQuestProgressFileStore.open(root.resolve("ledger"),world.binding(),world.catalog());
            try{if(fresh)ledger.commit(Optional.empty(),State.empty(),List.of(origin));require(ledger.origin().orElseThrow(()->new IOException("Recovery origin missing; refusing reset")).frame().snapshot().sources().equals(List.of(origin)),"Recovery archive belongs to another world");pending();}
            catch(IOException|RuntimeException|Error failure){try{ledger.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
        }
        byte[] blob(String hash)throws IOException {
            digestField(hash);Path path=blobs.resolve(hash+".gz");
            var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);require(attrs.isRegularFile() && attrs.size()>0 && attrs.size()<=LegacyPlayerDataFileReader.MAX_COMPRESSED_BYTES,"Invalid archived owner recovery blob");byte[] bytes;
            try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(LegacyPlayerDataFileReader.MAX_COMPRESSED_BYTES+1);}
            require(bytes.length==attrs.size() && digest(bytes).equals(hash),"Archived owner recovery blob is corrupt");return bytes;
        }
        void archive(byte[] bytes)throws IOException {
            String hash=digest(bytes);Path target=blobs.resolve(hash+".gz");
            if(exists(target)){require(Arrays.equals(blob(hash),bytes),"Existing owner recovery archive differs");return;}
            Path temporary=blobs.resolve("pending-"+UUID.randomUUID()+".tmp");writeForced(temporary,bytes);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);blob(hash);
        }

        Optional<Plan> pending()throws IOException{
            directory(root);directory(blobs);try(DirectoryStream<Path> entries=Files.newDirectoryStream(blobs)){for(Path entry:entries){String name=entry.getFileName().toString();require(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS) && (name.matches("[0-9a-f]{64}\\.gz") || name.matches("pending-[0-9a-f-]{36}\\.tmp")),"Unknown owner recovery blob entry");}}
            Head head=ledger.current().orElseThrow(()->new IOException("Empty owner recovery ledger"));var sources=head.frame().snapshot().sources();require(head.frame().snapshot().state().equals(State.empty()) && head.frame().revision()==sources.size() && !sources.isEmpty() && sources.getFirst().equals(origin),"Invalid owner recovery history");
            Plan pending=null;Set<UUID> seen=new HashSet<>();
            for(int i=1;i<sources.size();i++){
                SourceDocument source=sources.get(i);CompoundTag tag=decode(source.bytes());String kind=string(tag,"kind");require(tag.get("schema_version") instanceof IntTag && tag.getInt("schema_version")==1,"Unknown owner recovery schema");UUID id=uuid(string(tag,"operation"));require(source.identity().equals(PREFIX+id+"/"+kind),"Owner recovery archive identity mismatch");
                if(kind.equals("prepare")){require(pending==null && seen.add(id),"Overlapping or repeated owner recovery");pending=parse(source,tag,id);}
                else if(kind.equals("resolve")){
                    fields(tag,"schema_version","kind","operation","preparation_sha256","main_resolution_sha256");require(pending!=null && pending.id().equals(id) && pending.source().sha256().equals(string(tag,"preparation_sha256")),"Owner recovery resolution does not match intent");
                    var resolution=progress(world).historicalResolution(pending.transaction(),pending.head()).orElseThrow(()->new IOException("Resolved recovery lacks main decision"));require(resolution.decision()==pending.decision() && resolution.ledgerSha256().equals(string(tag,"main_resolution_sha256")),"Owner recovery chose another main decision");pending=null;
                }else throw new IOException("Unknown owner recovery record kind");
            }return Optional.ofNullable(pending);
        }
        Plan parse(SourceDocument source,CompoundTag tag,UUID id)throws IOException{
            fields(tag,"schema_version","kind","operation","base_revision","base_head","main_preparation_sha256","world","mirror_before","mirror_after");
            require(tag.get("base_revision") instanceof LongTag && tag.getLong("base_revision")>0,"Invalid owner recovery revision");long revision=tag.getLong("base_revision");String head=string(tag,"base_head");digestField(head);
            Pending transaction=progress(world).preparation(id,string(tag,"main_preparation_sha256"));require(transaction.authority()==Authority.WORLD_PLAYER && transaction.preparedRevision()==revision,"Owner recovery refers to another preparation");
            Head prepared=world.ledger().atRevision(revision).orElseThrow(()->new IOException("Owner recovery main preparation missing"));require(prepared.sha256().equals(head) && prepared.frame().snapshot().sources().getLast().sha256().equals(transaction.marker().preparationSha256()),"Owner recovery prepared head mismatch");
            Path players=world.worldDirectory().resolve("playerdata");Snapshot primary=LegacyPlayerDataFileReader.decodeArchived(world.worldDirectory(),transaction.actor(),Authority.WORLD_PLAYER,blob(string(tag,"world")));
            Snapshot before=LegacyPlayerDataFileReader.decodeArchived(players,transaction.actor(),Authority.PLAYER_FILE,blob(string(tag,"mirror_before"))),after=LegacyPlayerDataFileReader.decodeArchived(players,transaction.actor(),Authority.PLAYER_FILE,blob(string(tag,"mirror_after")));
            Decision decision=transaction.decisionFor(primary.data());transaction.decisionFor(before.data());require(transaction.decisionFor(after.data())==decision,"Owner recovery target has wrong transaction state");
            CompoundTag expected=LegacyPlayerNbtPatch.restoreCache(before.data(),primary.data());require(sameNbt(expected,after.data()),"Owner recovery changes foreign mirror data");
            if(sameNbt(expected,before.data()))require(before.sha256().equals(after.sha256()),"Unchanged mirror was reencoded");
            else require(Arrays.equals(LegacyPlayerNbtPatch.restoreMirror(before,primary),LegacyPlayerDataFileReader.inflateSingleMember(after.originalBytes())),"Owner recovery changed unrelated binary NBT");
            return new Plan(id,revision,head,transaction,decision,primary,before,after,source);
        }
        Plan prepare(Pending transaction,Head main,Snapshot primary,Snapshot before,Snapshot after)throws IOException{
            CompoundTag tag=record("prepare",transaction.transaction());tag.putLong("base_revision",main.frame().revision());tag.putString("base_head",main.sha256());tag.putString("main_preparation_sha256",transaction.marker().preparationSha256());tag.putString("world",primary.sha256());tag.putString("mirror_before",before.sha256());tag.putString("mirror_after",after.sha256());
            SourceDocument source=new SourceDocument(PREFIX+transaction.transaction()+"/prepare",encode(tag));Plan result=parse(source,tag,transaction.transaction());append(source);return result;
        }
        void resolve(Plan plan)throws IOException{CompoundTag tag=record("resolve",plan.id());tag.putString("preparation_sha256",plan.source().sha256());tag.putString("main_resolution_sha256",world.ledger().current().orElseThrow().sha256());append(new SourceDocument(PREFIX+plan.id()+"/resolve",encode(tag)));}
        void append(SourceDocument source)throws IOException{var head=ledger.current().orElseThrow();var sources=new ArrayList<>(head.frame().snapshot().sources());sources.add(source);ledger.commit(Optional.of(head.sha256()),State.empty(),sources);}
        @Override public void close()throws IOException{ledger.close();}
    }
    private static CompoundTag record(String kind,UUID id){CompoundTag tag=new CompoundTag();tag.putInt("schema_version",1);tag.putString("kind",kind);tag.putString("operation",id.toString());return tag;}
    private static byte[] encode(CompoundTag tag)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(bytes));return bytes.toByteArray();}
    private static CompoundTag decode(byte[] bytes)throws IOException{try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){CompoundTag tag=NbtIo.read(input,NbtAccounter.create(16L*1024*1024));require(tag!=null && input.read()==-1,"Malformed owner maintenance record");return tag;}catch(RuntimeException e){throw new IOException("Invalid owner maintenance NBT",e);}}
    private static String string(CompoundTag tag,String key)throws IOException{require(tag.get(key) instanceof StringTag,"Expected owner string: "+key);return tag.getString(key);}
    private static UUID uuid(String value)throws IOException{try{UUID id=UUID.fromString(value);require(id.toString().equals(value),"Noncanonical owner operation UUID");return id;}catch(IllegalArgumentException e){throw new IOException("Invalid owner operation UUID",e);}}
    private static void digestField(String value)throws IOException{require(value.matches("[0-9a-f]{64}"),"Invalid owner archive digest");}
    private static void fields(CompoundTag tag,String... fields)throws IOException{require(tag.getAllKeys().equals(Set.of(fields)),"Unknown owner maintenance fields");}
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}

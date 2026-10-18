package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.Resolution;

/** World-relative durable identity and historical decoding catalog. No automatic
 * creation, game startup/login hooks, native cache repair or player-file writes.
 * Offline recovery requires an external exclusion of ALL world/player writers.
 */
public final class LegacyQuestWorldStore implements AutoCloseable {
    public static final String DIRECTORY="gtnh-migration";
    public static final String MANIFEST="quest-world.bin";
    public static final String LEDGER="quest-progress";
    private static final String SOURCE_PREFIX="gtnh-world-binding:v1/";
    private static final byte[] MAGIC="GTNHQWB1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_MANIFEST=8*1024*1024,MAX_ROWS=100_000,MAX_TEXT=65_536;
    enum Boundary { DIRECTORY_CREATED, MANIFEST_PUBLISHED, GENESIS_PUBLISHED }
    @FunctionalInterface interface Observer { void reached(Boundary point)throws IOException; }
    private record Manifest(UUID id,Binding binding,Catalog catalog,byte[] bytes) {
        SourceDocument source(){return new SourceDocument(SOURCE_PREFIX+id,bytes);}
    }
    private final Path world;
    private final Manifest manifest;
    private final LegacyQuestProgressFileStore ledger;
    private LegacyQuestWorldStore(Path world,Manifest manifest,LegacyQuestProgressFileStore ledger){
        this.world=world;this.manifest=manifest;this.ledger=ledger;
    }
    public Path worldDirectory(){return world;}
    public UUID storageId(){return manifest.id();}
    public Binding binding(){return manifest.binding();}
    public Catalog catalog(){return manifest.catalog();}
    public LegacyQuestProgressFileStore ledger(){return ledger;}

    /** Explicit first-time creation only. Existing, partial and unknown metadata
     * are preserved and rejected. Initial state/import bytes must be validated by
     * the caller; this does not infer an empty state for an existing world.
     */
    public static LegacyQuestWorldStore create(Path world,Binding binding,Catalog catalog,State initial,List<SourceDocument> sources)throws IOException {
        return create(world,binding,catalog,initial,sources,point->{});
    }
    static LegacyQuestWorldStore create(Path directory,Binding binding,Catalog catalog,State initial,List<SourceDocument> sources,Observer observer)throws IOException {
        Path world=realDirectory(directory);
        Manifest manifest=manifest(UUID.randomUUID(),binding,catalog);
        List<SourceDocument> archive=new ArrayList<>();archive.add(manifest.source());
        for(SourceDocument source:sources){require(!source.identity().startsWith(SOURCE_PREFIX),"Reserved world binding source identity");archive.add(source);}
        // Validate caller data before creating any metadata.
        LegacyQuestProgressStoreCodec.write(new Frame(1,GENESIS,new Snapshot(binding,initial,archive)),catalog);
        Path root=world.resolve(DIRECTORY);
        Files.createDirectory(root);observer.reached(Boundary.DIRECTORY_CREATED);
        LegacyQuestProgressFileStore ledger=LegacyQuestProgressFileStore.open(root.resolve(LEDGER),binding,catalog);
        try {
            publish(root.resolve(MANIFEST),manifest.bytes());observer.reached(Boundary.MANIFEST_PUBLISHED);
            ledger.commit(Optional.empty(),initial,archive);observer.reached(Boundary.GENESIS_PUBLISHED);
            LegacyQuestWorldStore result=new LegacyQuestWorldStore(world,manifest,ledger);result.verifyBinding();return result;
        } catch(IOException|RuntimeException|Error failure){try{ledger.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
    }
    /** Reopen only: missing manifests/directories/genesis never become empty
     * progress. Historical catalog permits decoding before current config loads.
     * New submission must separately verify the current trusted binding/catalog.
     */
    public static LegacyQuestWorldStore open(Path directory)throws IOException {
        Path world=realDirectory(directory);Manifest manifest=readManifest(world);
        Path ledgerPath=realDirectory(world.resolve(DIRECTORY).resolve(LEDGER));
        var ledger=LegacyQuestProgressFileStore.open(ledgerPath,manifest.binding(),manifest.catalog());
        try {
            var result=new LegacyQuestWorldStore(world,manifest,ledger);result.verifyBinding();return result;
        } catch(IOException|RuntimeException|Error failure){try{ledger.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
    }
    public static LegacyQuestWorldStore open(Path directory,Binding current,Catalog catalog)throws IOException {
        var result=open(directory);
        try {result.verifyCurrent(current,catalog);return result;}
        catch(IOException|RuntimeException|Error failure){try{result.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
    }
    public void verifyCurrent(Binding current,Catalog catalog)throws IOException {
        synchronized(ledger){verifyBinding();require(manifest.binding().equals(current)
            && catalogSha256(manifest.catalog()).equals(catalogSha256(catalog)),"Current definitions or identities differ from world binding");}
    }
    public void verifyBinding()throws IOException {
        synchronized(ledger){
            Manifest actual=readManifest(world);
            require(Arrays.equals(manifest.bytes(),actual.bytes()),"World manifest changed while mounted");
            verifyLedger(world,ledger,manifest);
        }
    }
    /** Native submission preflight: arbitrary standalone or foreign-world ledgers
     * cannot be paired with this server's storage, even with matching hashes.
     */
    public static void requireBoundStore(Path world,LegacyQuestProgressFileStore ledger,Binding binding,Catalog catalog)throws IOException {
        synchronized(ledger){
            Path directory=realDirectory(world);Manifest manifest=readManifest(directory);
            require(manifest.binding().equals(binding) && catalogSha256(manifest.catalog()).equals(catalogSha256(catalog)),"Submission definitions differ from world binding");
            verifyLedger(directory,ledger,manifest);
        }
    }
    private static void verifyLedger(Path world,LegacyQuestProgressFileStore ledger,Manifest manifest)throws IOException {
        Path path=realDirectory(world.resolve(DIRECTORY).resolve(LEDGER));
        require(ledger.directory().equals(path),"Ledger belongs to another world directory");
        require(ledger.matches(manifest.binding(),manifest.catalog()),"Opened ledger uses a different decoding catalog");
        var head=ledger.current().orElseThrow(()->new IOException("Bound world ledger has no genesis; refusing empty progress"));
        require(head.frame().snapshot().binding().equals(manifest.binding()),"World/ledger binding mismatch");
        var origin=ledger.origin().orElseThrow(()->new IOException("World ledger origin missing"));
        var originals=origin.frame().snapshot().sources();
        require(!originals.isEmpty() && originals.getFirst().equals(manifest.source()),"World binding was not present at ledger genesis");
        var sources=head.frame().snapshot().sources();
        require(!sources.isEmpty() && sources.getFirst().equals(manifest.source()),"World manifest does not match ledger origin");
        for(int i=1;i<sources.size();i++)require(!sources.get(i).identity().startsWith(SOURCE_PREFIX),"Duplicate world binding source");
    }
    /** Offline-only decision using fresh primary bytes. WORLD_PLAYER additionally
     * requires a matching UUID mirror. Unknown mirrors remain pending; no refund,
     * player rewrite or native loaded-cache repair is guessed. This return value
     * is NOT a player-admission permit. Caller keeps exclusion after returning.
     */
    public Optional<Resolution> recoverOffline(Runnable assertOfflineExclusive)throws IOException {
        Objects.requireNonNull(assertOfflineExclusive);
        synchronized(ledger){
            assertOfflineExclusive.run();verifyBinding();
            var journal=new LegacyRetrievalTransactionJournal(ledger,binding(),catalog());
            var maybe=journal.pending();if(maybe.isEmpty())return Optional.empty();
            var pending=maybe.orElseThrow();
            var primary=pending.authority()==Authority.WORLD_PLAYER
                ?LegacyPlayerDataFileReader.readWorldPlayer(world,pending.actor())
                :LegacyPlayerDataFileReader.read(world.resolve("playerdata"),pending.actor());
            LegacyPlayerDataFileReader.Snapshot mirror=null;
            if(pending.authority()==Authority.WORLD_PLAYER){
                mirror=LegacyPlayerDataFileReader.read(world.resolve("playerdata"),pending.actor());
                CompoundTag a=primary.data(),b=mirror.data();
                require(a.getUUID("UUID").equals(b.getUUID("UUID")) && a.get("Inventory").equals(b.get("Inventory"))
                    && LegacyRetrievalTransactionJournal.readMarker(a).equals(LegacyRetrievalTransactionJournal.readMarker(b)),"World player and UUID mirror disagree; isolated repair required");
            }
            pending.decisionFor(primary.data());
            assertOfflineExclusive.run();verifyBinding();
            if(mirror!=null)mirror.verifyUnchanged();
            primary.verifyUnchanged();
            return Optional.of(journal.resolve(primary));
        }
    }
    @Override public void close()throws IOException {ledger.close();}

    private static Manifest manifest(UUID id,Binding binding,Catalog catalog)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.write(MAGIC);writeUUID(out,id);out.write(binding.definitionsSha256().getBytes(StandardCharsets.US_ASCII));out.write(binding.identitiesSha256().getBytes(StandardCharsets.US_ASCII));
            var quests=new TreeSet<>(catalog.quests());require(quests.size()<=MAX_ROWS,"World catalog exceeds supported bound");out.writeInt(quests.size());for(UUID quest:quests)writeUUID(out,quest);
            var tasks=catalog.definitions().stream().sorted(Comparator.comparing((Definition d)->d.key().questId()).thenComparingInt(d->d.key().index())).toList();
            require(tasks.size()<=MAX_ROWS,"World task catalog exceeds supported bound");out.writeInt(tasks.size());
            for(Definition task:tasks){writeUUID(out,task.key().questId());out.writeInt(task.key().index());writeText(out,task.key().factoryId());out.writeInt(task.requirementCount());require(bytes.size()<=MAX_MANIFEST,"World manifest exceeds supported bound");}
        }
        require(bytes.size()<=MAX_MANIFEST,"World manifest exceeds supported bound");
        return new Manifest(id,binding,catalog,bytes.toByteArray());
    }
    private static Manifest decode(byte[] bytes)throws IOException {
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){
            require(Arrays.equals(input.readNBytes(MAGIC.length),MAGIC),"Unknown world binding format");UUID id=readUUID(input);
            Binding binding=new Binding(new String(input.readNBytes(64),StandardCharsets.US_ASCII),new String(input.readNBytes(64),StandardCharsets.US_ASCII));
            int count=count(input);List<UUID> quests=new ArrayList<>();for(int i=0;i<count;i++)quests.add(readUUID(input));
            require(new HashSet<>(quests).size()==quests.size(),"Duplicate world quest definition");
            count=count(input);List<Definition> tasks=new ArrayList<>();for(int i=0;i<count;i++)tasks.add(new Definition(new TaskKey(readUUID(input),input.readInt(),readText(input)),input.readInt()));
            require(input.read()==-1,"Trailing world binding fields");Manifest result=manifest(id,binding,new Catalog(quests,tasks));
            require(Arrays.equals(bytes,result.bytes()),"Noncanonical world catalog encoding");return result;
        }catch(IllegalArgumentException e){throw new IOException("Invalid world binding data",e);}
    }
    private static int count(DataInputStream input)throws IOException{int value=input.readInt();require(value>=0 && value<=MAX_ROWS,"Invalid world catalog length");return value;}
    private static UUID readUUID(DataInputStream input)throws IOException{return new UUID(input.readLong(),input.readLong());}
    private static void writeUUID(DataOutputStream output,UUID id)throws IOException{output.writeLong(id.getMostSignificantBits());output.writeLong(id.getLeastSignificantBits());}
    private static String readText(DataInputStream input)throws IOException {
        int length=input.readInt();require(length>0 && length<=MAX_TEXT,"Invalid world factory text length");byte[] bytes=input.readNBytes(length);require(bytes.length==length,"Truncated factory text");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void writeText(DataOutputStream output,String text)throws IOException {
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);require(bytes.length>0 && bytes.length<=MAX_TEXT,"Unsupported factory text length");output.writeInt(bytes.length);output.write(bytes);
    }
    private static Manifest readManifest(Path world)throws IOException {
        Path root=realDirectory(world.resolve(DIRECTORY));
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){
            for(Path entry:entries){String name=entry.getFileName().toString();require(name.equals(MANIFEST) || name.equals(LEDGER) || name.matches("binding-[0-9a-f-]{36}\\.tmp"),"Unknown world binding entry: "+name);
                require(name.equals(LEDGER)?Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS):Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS),"Nonregular world binding entry");}
        }
        Path path=root.resolve(MANIFEST);BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size()>0 && before.size()<=MAX_MANIFEST,"Invalid world binding file");byte[] bytes;
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(MAX_MANIFEST+1);}
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(after.isRegularFile() && bytes.length==before.size() && before.size()==after.size() && Objects.equals(before.fileKey(),after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime()),"World binding changed while reading");
        return decode(bytes);
    }
    private static Path realDirectory(Path path)throws IOException {
        Path absolute=path.toAbsolutePath().normalize(),cursor=absolute.getRoot();
        for(Path part:absolute){cursor=cursor.resolve(part);require(Files.isDirectory(cursor,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(cursor),"Missing or linked world directory: "+cursor);}
        return absolute;
    }
    private static void publish(Path target,byte[] bytes)throws IOException {
        Path temporary=target.resolveSibling("binding-"+UUID.randomUUID()+".tmp");
        try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
        require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"World binding already exists");Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}

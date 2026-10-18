package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.nbt.*;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;

/** Immutable, source-complete archive. Catalog entries describe progress payloads,
 * not supported gameplay. Unknown tasks, rewards and presentation stay in raw
 * documents; consumers must check complete quest semantics before submission.
 */
public final class LegacyQuestDefinitionArchive {
    private static final int MAX_ARCHIVE=128*1024*1024;
    public record Pin(String archiveSha256,Binding binding) {
        public Pin { if(archiveSha256==null || !archiveSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid archive digest");Objects.requireNonNull(binding); }
        public static Pin read(byte[] bytes)throws IOException {
            String[] lines=utf8(bytes).split("\n",-1);
            require(lines.length==5 && lines[0].equals("GTNHQDP1") && lines[4].isEmpty(),"Invalid definition pin");
            try{return new Pin(lines[1],new Binding(lines[2],lines[3]));}catch(IllegalArgumentException e){throw new IOException("Invalid definition pin digests",e);}
        }
    }
    public record Identity(String legacyId,int damage) {}
    public record Problem(String document,String code) {}
    private final Pin pin;
    private final Map<String,byte[]> documents;
    private final Map<UUID,String> quests;
    private final byte[] references;
    private final Map<Identity,String> identities;
    private final Catalog catalog;
    private final List<Problem> problems;
    private LegacyQuestDefinitionArchive(Pin pin,Map<String,byte[]> documents,Map<UUID,String> quests,byte[] references,Map<Identity,String> identities)throws IOException {
        this.pin=pin;this.documents=Collections.unmodifiableMap(documents);this.quests=Collections.unmodifiableMap(quests);
        this.references=references;this.identities=Map.copyOf(identities);
        List<Problem> issues=new ArrayList<>();List<Definition> definitions=new ArrayList<>();
        byte[] settings=documents.get("QuestSettings.json");require(settings!=null,"Missing quest settings");
        var setting=LegacyTypedNbtJsonReader.inspect(settings,"QuestSettings.json");
        require(setting.accepted() && setting.nbt().orElseThrow().getString("format").equals("3.1.0"),"Unsupported quest settings format");
        for(var entry:quests.entrySet()) {
            var parsed=quest(entry.getKey());String path=entry.getValue();
            if(!parsed.accepted()){issues.add(new Problem(path,"typed_definition_rejected"));continue;}
            CompoundTag quest=parsed.nbt().orElseThrow();
            require(quest.get("questIDHigh") instanceof LongTag && quest.get("questIDLow") instanceof LongTag
                && new UUID(quest.getLong("questIDHigh"),quest.getLong("questIDLow")).equals(entry.getKey()),"Quest index differs from source document");
            if(!(quest.get("tasks") instanceof ListTag tasks) || (!tasks.isEmpty() && tasks.getElementType()!=Tag.TAG_COMPOUND)) {issues.add(new Problem(path,"invalid_task_list"));continue;}
            Set<Integer> indexes=new HashSet<>();List<Definition> current=new ArrayList<>();boolean valid=true;
            for(Tag tag:tasks) {
                CompoundTag task=(CompoundTag)tag;
                if(!(task.get("index") instanceof IntTag index) || index.getAsInt()<0 || !indexes.add(index.getAsInt()) || !(task.get("taskID") instanceof StringTag factory) || factory.getAsString().isEmpty()) {valid=false;break;}
                if(!factory.getAsString().equals("bq_standard:retrieval"))continue;
                if(!(task.get("requiredItems") instanceof ListTag items) || items.isEmpty() || items.getElementType()!=Tag.TAG_COMPOUND){valid=false;break;}
                current.add(new Definition(new TaskKey(entry.getKey(),index.getAsInt(),factory.getAsString()),items.size()));
            }
            if(valid)definitions.addAll(current);else issues.add(new Problem(path,"invalid_task_catalog"));
        }
        catalog=new Catalog(quests.keySet(),definitions);problems=List.copyOf(issues);
    }
    public static LegacyQuestDefinitionArchive loadPinned(Path serverDirectory)throws IOException {
        Pin pin;
        try(InputStream stream=LegacyQuestDefinitionArchive.class.getResourceAsStream("/gtnh_migration/quest-definitions.pin")) {
            require(stream!=null,"Missing bundled definition pin");byte[] bytes=stream.readNBytes(1025);require(bytes.length<=1024,"Oversized definition pin");pin=Pin.read(bytes);
        }
        Path root=serverDirectory.toAbsolutePath().normalize();
        for(String segment:List.of("config","gtnh_migration","quest-definitions")) {
            root=root.resolve(segment);require(Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory(),"Definition directory is not a real directory");
        }
        return read(root.resolve(pin.archiveSha256()+".bin"),pin);
    }
    public static LegacyQuestDefinitionArchive read(Path path,Pin pin)throws IOException {
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size()<=MAX_ARCHIVE,"Invalid definition archive file");
        byte[] bytes;
        try(InputStream stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(MAX_ARCHIVE+1);}
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(bytes.length<=MAX_ARCHIVE && bytes.length==before.size() && after.isRegularFile() && before.size()==after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(),after.fileKey()),"Definition archive changed while reading");
        return decode(bytes,pin);
    }
    static LegacyQuestDefinitionArchive decode(byte[] input,Pin pin)throws IOException {
        byte[] raw=input.clone();require(raw.length<=MAX_ARCHIVE && digest(raw).equals(pin.archiveSha256()),"Definition archive digest mismatch");
        try(var bytes=new ByteArrayInputStream(raw);var in=new DataInputStream(bytes)) {
            require(Arrays.equals(in.readNBytes(8),"GTNHQDA1".getBytes(StandardCharsets.US_ASCII)),"Unknown definition archive format");
            int count=count(in,100_000);Map<String,byte[]> documents=new LinkedHashMap<>();Map<UUID,String> quests=new LinkedHashMap<>();
            for(int n=0;n<count;n++) {
                String path=text(in,4096);require(!path.isEmpty() && !path.contains("\\") && !path.contains(":") && !path.contains("\0")
                    && Arrays.stream(path.split("/",-1)).noneMatch(part->part.isEmpty() || part.equals(".") || part.equals("..")),"Unsafe definition path");
                int kind=in.readUnsignedByte();require(kind==0 || kind==1,"Unknown definition kind");
                require((kind==1)==(path.startsWith("Quests/") && path.endsWith(".json")),"Quest path/index mismatch");
                UUID id=kind==1?new UUID(in.readLong(),in.readLong()):null;
                byte[] document=blob(in,LegacyTypedNbtJsonReader.MAX_INPUT_BYTES);
                require(HexFormat.of().formatHex(exact(in,32)).equals(digest(document)),"Document digest mismatch");
                require(documents.putIfAbsent(path,document)==null,"Duplicate document path");
                if(id!=null)require(quests.putIfAbsent(id,path)==null,"Duplicate quest identity");
            }
            int split=raw.length-bytes.available();
            require(digest(Arrays.copyOfRange(raw,0,split)).equals(pin.binding().definitionsSha256()),"Definition section digest mismatch");
            require(digest(Arrays.copyOfRange(raw,split,raw.length)).equals(pin.binding().identitiesSha256()),"Identity section digest mismatch");
            byte[] references=blob(in,64*1024*1024);int mappingCount=count(in,100_000);Map<Identity,String> mappings=new LinkedHashMap<>();
            for(int n=0;n<mappingCount;n++) {
                String old=text(in,4096);int damage=in.readInt();String target=text(in,4096);
                require(!old.isEmpty() && !target.isEmpty(),"Empty mapped identity");
                require(mappings.putIfAbsent(new Identity(old,damage),target)==null,"Duplicate mapped identity");
            }
            require(in.read()==-1,"Trailing definition archive data");
            return new LegacyQuestDefinitionArchive(pin,documents,quests,references,mappings);
        }catch(IllegalArgumentException e){throw new IOException("Malformed definition archive",e);}
    }
    public Pin pin(){return pin;}
    public Binding binding(){return pin.binding();}
    public Set<String> paths(){return documents.keySet();}
    public Set<UUID> questIds(){return quests.keySet();}
    public String questPath(UUID id){return Objects.requireNonNull(quests.get(id),"Unknown quest");}
    public byte[] document(String path){return Objects.requireNonNull(documents.get(path),"Unknown source document").clone();}
    public byte[] identityReferences(){return references.clone();}
    public Map<Identity,String> mappedIdentities(){return identities;}
    public LegacyTypedNbtJsonReader.Result quest(UUID id){String path=questPath(id);return LegacyTypedNbtJsonReader.inspect(documents.get(path),path);}
    /** Complete source flags and metadata; this does not authorize task execution. */
    public LegacyTypedNbtJsonReader.Result settings(){return LegacyTypedNbtJsonReader.inspect(documents.get("QuestSettings.json"),"QuestSettings.json");}
    public Catalog progressCatalog(){return catalog;}
    public List<Problem> catalogProblems(){return problems;}
    private static int count(DataInputStream in,int max)throws IOException {int n=in.readInt();require(n>=0 && n<=max,"Archive count outside limit");return n;}
    private static byte[] blob(DataInputStream in,int max)throws IOException {return exact(in,count(in,max));}
    private static byte[] exact(DataInputStream in,int size)throws IOException {byte[] value=in.readNBytes(size);require(value.length==size,"Truncated archive field");return value;}
    private static String text(DataInputStream in,int max)throws IOException {return utf8(blob(in,max));}
    private static String utf8(byte[] raw)throws IOException {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}
    private static void require(boolean condition,String message)throws IOException {if(!condition)throw new IOException(message);}
}

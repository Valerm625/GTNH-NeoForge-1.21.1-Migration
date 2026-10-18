package org.gtnhmigration.compat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

public final class LegacyQuestDefinitionArchiveCheck {
    static int checks,rejections;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    interface Action {void run()throws Exception;}
    static void reject(Action action)throws Exception {try{action.run();throw new AssertionError("Accepted malformed archive");}catch(IOException expected){checks++;rejections++;}}
    static final byte[] SETTINGS="{\"format:8\":\"3.1.0\",\"betterquesting:10\":{\"editMode:1\":0}}".getBytes(StandardCharsets.UTF_8);
    record Entry(String path,int kind,UUID id,byte[] bytes) {}
    record Bundle(byte[] bytes,LegacyQuestDefinitionArchive.Pin pin) {}
    static void blob(DataOutputStream out,byte[] bytes)throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    static void text(DataOutputStream out,String value)throws IOException {blob(out,value.getBytes(StandardCharsets.UTF_8));}
    static Bundle bundle(List<Entry> entries,List<String[]> mappings,boolean trailing)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBytes("GTNHQDA1");out.writeInt(entries.size());
        for(var e:entries){text(out,e.path());out.writeByte(e.kind());if(e.kind()==1){out.writeLong(e.id().getMostSignificantBits());out.writeLong(e.id().getLeastSignificantBits());}blob(out,e.bytes());out.write(HexFormat.of().parseHex(digest(e.bytes())));}
        int split=bytes.size();blob(out,"[]".getBytes(StandardCharsets.UTF_8));out.writeInt(mappings.size());for(var m:mappings){text(out,m[0]);out.writeInt(0);text(out,m[1]);}if(trailing)out.writeByte(1);
        byte[] raw=bytes.toByteArray();return new Bundle(raw,new LegacyQuestDefinitionArchive.Pin(digest(raw),new Binding(digest(Arrays.copyOfRange(raw,0,split)),digest(Arrays.copyOfRange(raw,split,raw.length)))));
    }
    static Entry settings(){return new Entry("QuestSettings.json",0,null,SETTINGS);}
    static Entry quest(String path,long id,String tasks){return new Entry(path,1,new UUID(0,id),("{\"questIDHigh:4\":0,\"questIDLow:4\":"+id+",\"tasks:9\":"+tasks+"}").getBytes(StandardCharsets.UTF_8));}
    static LegacyQuestDefinitionArchive decode(Bundle b)throws Exception{return LegacyQuestDefinitionArchive.decode(b.bytes(),b.pin());}
    static void malformed()throws Exception {
        var base=bundle(List.of(settings()),List.of(),false);check(decode(base).questIds().isEmpty(),"No-task archive");
        byte[] changed=base.bytes().clone();changed[0]^=1;reject(()->LegacyQuestDefinitionArchive.decode(changed,base.pin()));
        reject(()->LegacyQuestDefinitionArchive.decode(base.bytes(),new LegacyQuestDefinitionArchive.Pin(base.pin().archiveSha256(),new Binding("0".repeat(64),base.pin().binding().identitiesSha256()))));
        reject(()->LegacyQuestDefinitionArchive.decode(base.bytes(),new LegacyQuestDefinitionArchive.Pin(base.pin().archiveSha256(),new Binding(base.pin().binding().definitionsSha256(),"0".repeat(64)))));
        for(String path:List.of("../x","/x","a//b","a/./b","a/../b","a\\b","C:x","a/",""))reject(()->decode(bundle(List.of(settings(),new Entry(path,0,null,new byte[0])),List.of(),false)));
        reject(()->decode(bundle(List.of(settings(),settings()),List.of(),false)));
        reject(()->decode(bundle(List.of(),List.of(),false)));
        reject(()->decode(bundle(List.of(settings()),List.of(new String[]{"minecraft:dirt","minecraft:dirt"},new String[]{"minecraft:dirt","minecraft:stone"}),false)));
        reject(()->decode(bundle(List.of(settings()),List.of(),true)));
        var q=quest("Quests/a.json",1,"{}");
        reject(()->decode(bundle(List.of(settings(),q,quest("Quests/b.json",1,"{}")),List.of(),false)));
        reject(()->decode(bundle(List.of(settings(),new Entry(q.path(),0,null,q.bytes())),List.of(),false)));
        reject(()->decode(bundle(List.of(settings(),new Entry("x.json",1,q.id(),q.bytes())),List.of(),false)));
        reject(()->decode(bundle(List.of(settings(),new Entry(q.path(),1,new UUID(0,2),q.bytes())),List.of(),false)));
        var malformed=decode(bundle(List.of(settings(),new Entry(q.path(),1,q.id(),"{".getBytes(StandardCharsets.UTF_8))),List.of(),false));
        check(malformed.questIds().contains(q.id()) && !malformed.quest(q.id()).accepted() && malformed.catalogProblems().size()==1,"Rejected typed document lost or activated");
        String task="{\"index:3\":7,\"taskID:8\":\"bq_standard:retrieval\",\"requiredItems:9\":{\"0:10\":{}}}";
        var ordered=decode(bundle(List.of(settings(),quest(q.path(),1,"{\"0:10\":"+task+",\"1:10\":{\"index:3\":2,\"taskID:8\":\"future:task\"}}")),List.of(),false));
        check(ordered.catalogProblems().isEmpty() && ordered.progressCatalog().definitions().size()==1 && ordered.progressCatalog().definitions().iterator().next().key().index()==7,"Task index used list ordinal or fabricated unknown task");
        check(ordered.quest(q.id()).nbt().orElseThrow().getList("tasks",10).size()==2,"Unknown task removed");
        var duplicate=decode(bundle(List.of(settings(),quest(q.path(),1,"{\"0:10\":"+task+",\"1:10\":"+task+"}")),List.of(),false));
        check(duplicate.catalogProblems().size()==1 && duplicate.progressCatalog().definitions().isEmpty(),"Duplicate index partially cataloged");
        for(String pin:List.of("", "GTNHQDP1\n", "GTNHQDP2\n"+"0".repeat(64)+"\n"+"0".repeat(64)+"\n"+"0".repeat(64)+"\n"))reject(()->LegacyQuestDefinitionArchive.Pin.read(pin.getBytes(StandardCharsets.UTF_8)));
        byte[] truncated=Arrays.copyOf(base.bytes(),base.bytes().length-1);reject(()->LegacyQuestDefinitionArchive.decode(truncated,new LegacyQuestDefinitionArchive.Pin(digest(truncated),base.pin().binding())));
    }
    public static void main(String[] args)throws Exception {
        Path runtime=Path.of(args[0]),source=Path.of(args[1]),scratch=Path.of(args[2]);
        var archive=LegacyQuestDefinitionArchive.loadPinned(runtime);
        check(archive.paths().size()==7806 && archive.questIds().size()==3814,"Incomplete source archive");
        check(archive.catalogProblems().isEmpty(),"Source catalog diagnostics: "+archive.catalogProblems());
        for(String path:archive.paths()){byte[] actual=archive.document(path);check(Arrays.equals(actual,Files.readAllBytes(source.resolve(path))),"Source bytes differ: "+path);if(actual.length>0){actual[0]^=1;check(!Arrays.equals(actual,archive.document(path)),"Mutable raw source");}}
        for(UUID id:archive.questIds()) {var parsed=archive.quest(id);check(parsed.accepted(),"Real typed quest rejected");var nbt=parsed.nbt().orElseThrow();check(nbt.getLong("questIDHigh")==id.getMostSignificantBits() && nbt.getLong("questIDLow")==id.getLeastSignificantBits(),"Source identity mismatch");}
        var first=archive.quest(new UUID(0,0)).nbt().orElseThrow();check(first.getList("tasks",10).getCompound(0).getList("requiredItems",10).getCompound(0).getInt("Count")==8,"Real dirt count");
        first.putString("localMutation","x");check(!archive.quest(new UUID(0,0)).nbt().orElseThrow().contains("localMutation"),"Mutable decoded quest");
        var sticks=archive.quest(new UUID(0,2)).nbt().orElseThrow();check(sticks.getCompound("properties").getCompound("betterquesting").getBoolean("globalShare"),"Lost source globalShare");
        check(sticks.getList("tasks",10).getCompound(0).getList("requiredItems",10).getCompound(1).getString("OreDict").equals("logWood"),"Ore requirement flattened");
        check(archive.mappedIdentities().size()==720 && archive.mappedIdentities().get(new LegacyQuestDefinitionArchive.Identity("minecraft:dirt",0)).equals("minecraft:dirt"),"Identity map differs");
        byte[] refs=archive.identityReferences();refs[0]^=1;check(!Arrays.equals(refs,archive.identityReferences()),"Mutable raw identity references");
        Path world=scratch.resolve("world");Files.createDirectories(world);
        try(var store=LegacyQuestWorldStore.create(world,archive.binding(),archive.progressCatalog(),LegacyQuestProgressEnvelope.State.empty(),List.of(new SourceDocument("definition-pin",Files.readAllBytes(Path.of(args[3])))))) {
            store.verifyCurrent(archive.binding(),archive.progressCatalog());check(true,"Current archive rejected");
            var before=store.ledger().current();reject(()->store.verifyCurrent(new Binding("0".repeat(64),archive.binding().identitiesSha256()),archive.progressCatalog()));check(store.ledger().current().equals(before),"Mismatch changed ledger");
        }
        try(var store=LegacyQuestWorldStore.open(world,archive.binding(),archive.progressCatalog())){check(store.catalog().quests().size()==3814,"Reopen lost quest catalog");}
        reject(()->LegacyQuestDefinitionArchive.read(scratch,archive.pin()));
        malformed();
        System.out.println("{\"test\":\"LegacyQuestDefinitionArchiveCheck\",\"passed\":true,\"assertions\":"+checks+",\"rejections\":"+rejections+",\"source_documents\":"+archive.paths().size()+",\"source_quests\":"+archive.questIds().size()+",\"progress_retrieval_definitions\":"+archive.progressCatalog().definitions().size()+",\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}

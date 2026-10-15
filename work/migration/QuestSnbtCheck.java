import java.nio.file.*;
import java.util.*;
import java.io.*;
import com.google.gson.*;
import net.minecraft.nbt.*;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
/** Exercise actual FTB SNBT parser/writer against exported on-disk data. No game world/EULA bypass. */
public class QuestSnbtCheck {
    static JsonElement json(Tag t){
        if(t instanceof CompoundTag c){JsonObject o=new JsonObject();for(String k:c.getAllKeys())o.add(k,json(c.get(k)));return o;}
        if(t instanceof ListTag list){JsonArray a=new JsonArray();for(Tag v:list)a.add(json(v));return a;}
        if(t instanceof StringTag s)return new JsonPrimitive(s.getAsString());
        if(t instanceof NumericTag n)return new JsonPrimitive(n.getAsNumber());
        throw new IllegalArgumentException("Unexpected NBT type "+t);
    }
    public static void main(String[] args)throws Exception{
        Path folder=Path.of(args[0]),out=Path.of(args[1]);JsonObject parsed=new JsonObject();int roundtrips=0,quests=0,tasks=0,rewards=0;Set<String> ids=new HashSet<>();
        List<CompoundTag> questTags=new ArrayList<>();
        try(var stream=Files.walk(folder)){
            for(Path p:stream.filter(x->x.toString().endsWith(".snbt")).sorted().toList()){
                CompoundTag data=SNBT.tryRead(p);if(data==null)throw new AssertionError("SNBT read null "+p);
                CompoundTag round=SNBT.readLines(SNBT.writeLines(data));if(!data.equals(round))throw new AssertionError("SNBT roundtrip changed "+p);
                String rel=folder.relativize(p).toString().replace('\\','/');parsed.add(rel,json(data));roundtrips++;
                if(rel.startsWith("chapters/")){
                    if(!ids.add(data.getString("id")))throw new AssertionError("Duplicate chapter ID");
                    for(Tag q:data.getList("quests",Tag.TAG_COMPOUND)){
                        CompoundTag quest=(CompoundTag)q;if(!ids.add(quest.getString("id")))throw new AssertionError("Duplicate quest ID");questTags.add(quest);quests++;
                        for(Tag task:quest.getList("tasks",Tag.TAG_COMPOUND)){if(!ids.add(((CompoundTag)task).getString("id")))throw new AssertionError("Duplicate task ID");tasks++;}
                        for(Tag reward:quest.getList("rewards",Tag.TAG_COMPOUND)){if(!ids.add(((CompoundTag)reward).getString("id")))throw new AssertionError("Duplicate reward ID");rewards++;}
                    }
                }
            }
        }
        Set<String> questIds=new HashSet<>();for(CompoundTag q:questTags)questIds.add(q.getString("id"));
        for(CompoundTag q:questTags)for(Tag dep:q.getList("dependencies",Tag.TAG_STRING))if(!questIds.contains(dep.getAsString()))throw new AssertionError("Unresolved active prerequisite "+dep);
        Files.writeString(out,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(parsed));
        System.out.println("{\"test\":\"actual_ftb_snbt_parser_roundtrip\",\"passed\":true,\"files\":"+roundtrips+",\"quests\":"+quests+",\"tasks\":"+tasks+",\"rewards\":"+rewards+",\"dependency_ids_resolved\":true,\"game_quest_initialization_verified\":false}");
    }
}

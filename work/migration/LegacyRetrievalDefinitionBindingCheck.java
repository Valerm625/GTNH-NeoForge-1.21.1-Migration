package org.gtnhmigration.compat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyRetrievalInventoryBridge.FrozenStack;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

/** Actual source definitions and vanilla items; authorization remains a fixture. */
public final class LegacyRetrievalDefinitionBindingCheck {
    static int assertions,sourceComparisons,boundQuests;static final UUID ACTOR=new UUID(0,99);
    static HolderLookup.Provider registries;static LegacyQuestDefinitionArchive archive;
    static final LegacyQuestRetrievalPlanner.Gate OPEN=new LegacyQuestRetrievalPlanner.Gate(true,true,true,false);
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static UUID id(long low){return new UUID(0,low);}
    static LegacyRetrievalDefinitionBinding.Result bind(long low){return LegacyRetrievalDefinitionBinding.inspect(archive,id(low),registries);}
    static JsonObject json(long low){return JsonParser.parseString(new String(archive.document(archive.questPath(id(low))),StandardCharsets.UTF_8)).getAsJsonObject();}
    static JsonObject task(JsonObject q){return q.getAsJsonObject("tasks:9").getAsJsonObject("0:10");}
    static JsonObject item(JsonObject q){return task(q).getAsJsonObject("requiredItems:9").getAsJsonObject("0:10");}
    static void blob(DataOutputStream out,byte[] bytes)throws IOException{out.writeInt(bytes.length);out.write(bytes);}
    static void text(DataOutputStream out,String text)throws IOException{blob(out,text.getBytes(StandardCharsets.UTF_8));}
    static LegacyQuestDefinitionArchive fixture(JsonObject q,boolean edit,Map<LegacyQuestDefinitionArchive.Identity,String> mappings)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBytes("GTNHQDA1");out.writeInt(2);
        byte[] settings=("{\"format:8\":\"3.1.0\",\"betterquesting:10\":{\"editMode:1\":"+(edit?1:0)+"}}").getBytes(StandardCharsets.UTF_8);
        for(String path:List.of("QuestSettings.json","Quests/source.json")){
            boolean quest=path.startsWith("Quests/");text(out,path);out.writeByte(quest?1:0);
            if(quest){out.writeLong(q.get("questIDHigh:4").getAsLong());out.writeLong(q.get("questIDLow:4").getAsLong());}
            byte[] raw=quest?q.toString().getBytes(StandardCharsets.UTF_8):settings;blob(out,raw);out.write(HexFormat.of().parseHex(digest(raw)));
        }
        int split=bytes.size();blob(out,"[]".getBytes(StandardCharsets.UTF_8));out.writeInt(mappings.size());
        for(var e:mappings.entrySet()){text(out,e.getKey().legacyId());out.writeInt(e.getKey().damage());text(out,e.getValue());}
        byte[] raw=bytes.toByteArray();return LegacyQuestDefinitionArchive.decode(raw,new LegacyQuestDefinitionArchive.Pin(digest(raw),new Binding(digest(Arrays.copyOfRange(raw,0,split)),digest(Arrays.copyOfRange(raw,split,raw.length)))));
    }
    static LegacyRetrievalDefinitionBinding.Result changed(JsonObject q)throws Exception{return changed(q,false,archive.mappedIdentities());}
    static LegacyRetrievalDefinitionBinding.Result changed(JsonObject q,boolean edit,Map<LegacyQuestDefinitionArchive.Identity,String> mappings)throws Exception{
        return LegacyRetrievalDefinitionBinding.inspect(fixture(q,edit,mappings),new UUID(q.get("questIDHigh:4").getAsLong(),q.get("questIDLow:4").getAsLong()),registries);
    }
    static void rejected(LegacyRetrievalDefinitionBinding.Result result,String code){check(result.bound().isEmpty() && result.problems().stream().anyMatch(p->p.code().equals(code)),"Expected "+code+": "+result.problems());check(result.definition().bytes().length>0 && result.settings().bytes().length>0,"Lost raw sources on rejection");}
    static LegacyRetrievalInventoryBridge.Prepared prepare(LegacyRetrievalDefinitionBinding.BoundQuest bound,Inventory inv){return LegacyRetrievalInventoryBridge.prepare(bound.quest(),Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inv),registries,OPEN);}
    static boolean matches(LegacyRetrievalDefinitionBinding.BoundQuest bound,ItemStack stack){return bound.quest().tasks().getFirst().retrieval().requirements().getFirst().matches().test(FrozenStack.of(stack,registries));}
    static void realSources()throws Exception {
        var dirt=bind(0).requireBound();Inventory inv=new Inventory(null);inv.items.set(0,new ItemStack(Items.DIRT,7));check(!prepare(dirt,inv).plan().allTasksCompleteForActor(),"7 dirt completed 8-dirt task");inv.items.set(3,new ItemStack(Items.DIRT));
        var detection=prepare(dirt,inv);check(detection.plan().allTasksCompleteForActor() && detection.plan().consumed().isEmpty(),"Real nonconsuming detection");check(inv.items.get(0).getCount()==7,"Binding/planning changed inventory");
        var stone=bind(8).requireBound();check(stone.quest().globalShare() && !stone.quest().global(),"Real globalShare changed");check(stone.requirements().getFirst().count()==64,"Real count");
        var charcoal=bind(27).requireBound();check(charcoal.requirements().getFirst().legacy().damage()==1 && charcoal.requirements().getFirst().targetId().equals("minecraft:charcoal"),"Coal metadata flattened");
        check(!matches(charcoal,new ItemStack(Items.COAL)) && matches(charcoal,new ItemStack(Items.CHARCOAL)),"Coal substituted for charcoal");
        Inventory fuel=new Inventory(null);fuel.items.set(0,new ItemStack(Items.CHARCOAL,3));fuel.items.set(2,new ItemStack(Items.CHARCOAL,3));fuel.offhand.set(0,new ItemStack(Items.CHARCOAL,64));
        var delivery=prepare(charcoal,fuel);check(delivery.plan().allTasksCompleteForActor() && delivery.plan().consumed().stream().mapToInt(c->c.count()).sum()==4,"Source 4-charcoal plan");delivery.applyCountsWithoutPublishing(()->true);
        check(fuel.items.get(0).isEmpty() && fuel.items.get(2).getCount()==2 && fuel.offhand.get(0).getCount()==64,"Source-backed count application");
        var replay=LegacyRetrievalInventoryBridge.prepare(charcoal.quest(),delivery.plan().afterStates(),ACTOR,List.of(ACTOR),Map.of(ACTOR,fuel),registries,OPEN);check(replay.plan().consumed().isEmpty(),"Source task replay consumed again");
        var bulk=bind(474).requireBound();Inventory stacks=new Inventory(null);for(int i=0;i<5;i++)stacks.items.set(i,new ItemStack(Items.COBBLESTONE,64));
        var bulkPlan=prepare(bulk,stacks);check(bulkPlan.plan().consumed().stream().mapToInt(c->c.count()).sum()==256,"Large requirement was clamped");bulkPlan.applyCountsWithoutPublishing(()->true);check(stacks.items.stream().mapToInt(ItemStack::getCount).sum()==64,"Bulk source delivery lost extra stack");
        rejected(bind(2),"ore_set_mapping_missing");check(bind(2).problems().stream().noneMatch(p->p.code().equals("unsupported_empty_quest")),"Unresolved items mislabeled as empty source quest");check(archive.quest(id(2)).nbt().orElseThrow().getList("tasks",10).getCompound(0).getList("requiredItems",10).getCompound(1).getString("OreDict").equals("logWood"),"Original ore requirement lost");
        var multi=bind(1914).requireBound();Inventory supplies=new Inventory(null);supplies.items.set(0,new ItemStack(Items.EMERALD,64));supplies.items.set(1,new ItemStack(Items.BOOK,10));
        var payment=prepare(multi,supplies);check(payment.plan().allTasksCompleteForActor() && payment.plan().consumed().stream().mapToInt(c->c.count()).sum()==72,"Real multi-requirement consumption");payment.applyCountsWithoutPublishing(()->true);
        check(supplies.items.get(0).isEmpty() && supplies.items.get(1).getCount()==2,"Source emerald/book counts differ");
        byte[] raw=bind(0).definition().bytes();raw[0]^=1;check(Arrays.equals(bind(0).definition().bytes(),archive.document(archive.questPath(id(0)))),"Definition leaked mutable raw data");
        check(dirt.binding().equals(archive.binding()) && dirt.archiveSha256().equals(archive.pin().archiveSha256()),"Binding provenance missing");
    }
    static void predicates()throws Exception {
        var strict=bind(0).requireBound();ItemStack named=new ItemStack(Items.DIRT);named.set(DataComponents.CUSTOM_NAME,Component.literal("extra"));check(!matches(strict,named),"Partial empty NBT accepted name");
        ItemStack empty=new ItemStack(Items.DIRT);empty.set(DataComponents.CUSTOM_DATA,CustomData.EMPTY);check(matches(strict,empty),"Empty custom NBT differs from absent");
        CompoundTag custom=new CompoundTag();custom.putInt("source",7);ItemStack extra=new ItemStack(Items.DIRT);extra.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));check(!matches(strict,extra),"Unexpected custom NBT accepted");
        JsonObject q=json(0);task(q).remove("ignoreNBT:1");task(q).remove("partialMatch:1");var defaults=changed(q).requireBound();check(!defaults.requirements().getFirst().ignoreNBT() && !defaults.requirements().getFirst().partialMatch() && !matches(defaults,named),"Constructor defaults substituted for readFromNBT");
        JsonObject tags=new JsonObject();tags.addProperty("source:3",7);item(q).add("tag:10",tags);rejected(changed(q),"legacy_nbt_component_mapping_missing");task(q).addProperty("ignoreNBT:1",1);var ignoring=changed(q).requireBound();check(matches(ignoring,named) && matches(ignoring,extra),"Explicit ignoreNBT did not ignore tags");
        q=json(0);item(q).addProperty("id:8","minecraft:iron_pickaxe");var maps=new LinkedHashMap<>(archive.mappedIdentities());maps.put(new LegacyQuestDefinitionArchive.Identity("minecraft:iron_pickaxe",0),"minecraft:iron_pickaxe");var tool=changed(q,false,maps).requireBound();
        ItemStack worn=new ItemStack(Items.IRON_PICKAXE);worn.setDamageValue(12);check(matches(tool,worn),"Durability treated as compared NBT");worn.set(DataComponents.CUSTOM_NAME,Component.literal("extra"));check(!matches(tool,worn),"Durability normalization removed other components");
        for(boolean partial:List.of(false,true)){q=json(0);task(q).addProperty("partialMatch:1",partial?1:0);check(!matches(changed(q).requireBound(),extra),"Empty required NBT became wildcard");}
    }
    static void orderAndRejections()throws Exception {
        JsonObject q=json(27),consume=task(q);consume.addProperty("index:3",5);JsonObject observe=consume.deepCopy();observe.addProperty("index:3",2);observe.addProperty("consume:1",0);observe.getAsJsonObject("requiredItems:9").getAsJsonObject("0:10").addProperty("Count:3",5);q.getAsJsonObject("tasks:9").add("1:10",observe);
        var bound=changed(q).requireBound();check(bound.quest().tasks().get(0).index()==5 && bound.quest().tasks().get(1).index()==2,"Source task declaration order lost");
        Inventory inv=new Inventory(null);inv.items.set(0,new ItemStack(Items.CHARCOAL,5));var plan=prepare(bound,inv).plan();check(plan.detections().get(0).taskIndex()==2 && plan.allTasksCompleteForActor(),"Actual task index ignored");
        consume.addProperty("index:3",1);check(!prepare(changed(q).requireBound(),inv).plan().allTasksCompleteForActor(),"Reverse task order no longer matters");
        JsonObject unknown=new JsonObject();unknown.addProperty("index:3",10);unknown.addProperty("taskID:8","future:unknown");q.getAsJsonObject("tasks:9").add("2:10",unknown);rejected(changed(q),"unsupported_task_type");
        q=json(0);q.getAsJsonObject("tasks:9").add("1:10",task(q).deepCopy());rejected(changed(q),"invalid_or_duplicate_task_index");
        for(String flag:List.of("autoConsume","groupDetect","requireOnlyOneItem")){q=json(0);task(q).addProperty(flag+":1",1);var cfg=changed(q).requireBound().quest().tasks().getFirst().retrieval();check(switch(flag){case "autoConsume"->cfg.autoConsume();case "groupDetect"->cfg.groupDetect();default->cfg.requireOnlyOneItem();},"Source mode flag lost");}
        for(String flag:List.of("isGlobal","simultaneous")){q=json(0);q.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10").addProperty(flag+":1",1);rejected(changed(q),flag.equals("isGlobal")?"unsupported_global":"unsupported_simultaneous");}
        q=json(0);q.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10").addProperty("repeatTime:3",0);rejected(changed(q),"unsupported_repeat");
        q=json(0);q.getAsJsonObject("properties:10").getAsJsonObject("betterquesting:10").addProperty("taskLogic:8","OR");rejected(changed(q),"unsupported_task_logic");rejected(changed(json(0),true,archive.mappedIdentities()),"unsupported_edit_mode");
        q=json(0);task(q).addProperty("futureOption:1",1);rejected(changed(q),"unknown_definition_field");
        q=json(0);item(q).addProperty("id:8","unknown:source");rejected(changed(q),"legacy_identity_mapping_missing");
        for(String target:List.of("unknown:missing","minecraft:air","INVALID ID")){var mappings=new LinkedHashMap<>(archive.mappedIdentities());mappings.put(new LegacyQuestDefinitionArchive.Identity("minecraft:dirt",0),target);rejected(changed(json(0),false,mappings),target.equals("minecraft:air")?"empty_mapped_target":target.equals("INVALID ID")?"invalid_target_identity":"mapped_target_not_registered");}
        q=json(0);item(q).addProperty("Count:3",0);rejected(changed(q),"unsupported_requirement_count");q=json(0);item(q).addProperty("Damage:2",32767);rejected(changed(q),"wildcard_metadata_set_mapping_missing");
        q=json(0);task(q).remove("ignoreNBT:1");task(q).addProperty("ignoreNBT:8","true");rejected(changed(q),"unsupported_flag_type");
        q=json(0);q.remove("properties:10");var defaultProperties=changed(q).requireBound().quest();check(defaultProperties.taskLogic().equals("AND") && defaultProperties.repeatTime()==-1 && !defaultProperties.globalShare(),"Source property defaults lost");
    }
    static Map<String,Object> census() {
        Map<String,Integer> codes=new TreeMap<>();List<String> boundIds=new ArrayList<>();
        for(UUID id:new TreeSet<>(archive.questIds())){
            var result=LegacyRetrievalDefinitionBinding.inspect(archive,id,registries);check(Arrays.equals(result.definition().bytes(),archive.document(archive.questPath(id))),"Census raw preservation");
            for(var p:result.problems())codes.merge(p.code(),1,Integer::sum);
            if(result.bound().isEmpty())continue;boundQuests++;boundIds.add(id.toString());var bound=result.requireBound();check(bound.quest().tasks().size()==archive.quest(id).nbt().orElseThrow().getList("tasks",10).size(),"Partially bound quest");
            CompoundTag definition=archive.quest(id).nbt().orElseThrow();
            for(Tag tag:definition.getList("tasks",10)){
                CompoundTag source=(CompoundTag)tag;var reference=new BqTaskDefinitionReadReference();reference.readFromNBT(new BqTaskDefinitionReadReference.NBTTagCompound(source));
                int index=source.getInt("index");var task=bound.quest().tasks().stream().filter(t->t.index()==index).findFirst().orElseThrow();
                check(task.retrieval().consume()==reference.consume && task.retrieval().autoConsume()==reference.autoConsume && task.retrieval().groupDetect()==reference.groupDetect && task.retrieval().requireOnlyOneItem()==reference.requireOnlyOneItem,"Source flag disagreement");
                var identities=bound.requirements().stream().filter(r->r.taskIndex()==index).toList();check(identities.size()==reference.requiredItems.size(),"Source requirement count disagreement");
                for(var identity:identities){check(identity.ignoreNBT()==reference.ignoreNBT && identity.partialMatch()==reference.partialMatch,"Source NBT flags disagreement");check(identity.count()==reference.requiredItems.get(identity.ordinal()).getInt("Count"),"Source count disagreement");}
                sourceComparisons++;
            }
        }
        check(boundQuests>0,"No actual definition bound");return Map.of("bound_detection_quest_ids",boundIds,"unresolved_problem_occurrences",codes);
    }
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);archive=LegacyQuestDefinitionArchive.loadPinned(Path.of(args[0]));
        realSources();predicates();orderAndRejections();var census=census();
        Map<String,Object> report=new LinkedHashMap<>();report.put("test","LegacyRetrievalDefinitionBindingCheck");report.put("passed",true);report.put("assertions",assertions);report.put("source_read_comparisons",sourceComparisons);report.put("actual_source_quests_inspected",archive.questIds().size());report.put("bound_vanilla_detection_declarations",boundQuests);report.putAll(census);
        report.put("runtime_integrated",false);report.put("export_enabled",false);report.put("game_runtime_verified",false);report.put("full_parity",false);System.out.println(new Gson().toJson(report));
    }
}

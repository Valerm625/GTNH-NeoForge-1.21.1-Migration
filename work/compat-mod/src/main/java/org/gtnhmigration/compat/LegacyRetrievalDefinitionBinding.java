package org.gtnhmigration.compat;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.ItemStack;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;
import org.gtnhmigration.compat.LegacyRetrievalInventoryBridge.FrozenStack;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

/** Bind source-backed manual retrieval declarations, without authorizing or
 * executing a submission. Raw quests/settings remain available on rejection.
 * Party membership, prerequisites, completion, rewards and UI are separate
 * source requirements; a bound detection declaration does not implement them.
 */
public final class LegacyRetrievalDefinitionBinding {
    private static final Set<String> TASK_FIELDS=Set.of("index","taskID","requiredItems","ignoreNBT","partialMatch","consume","autoConsume","groupDetect","requireOnlyOneItem");
    private static final Set<String> ITEM_FIELDS=Set.of("id","Count","Damage","OreDict","tag");
    private LegacyRetrievalDefinitionBinding() {}
    public record Problem(String path,String code) {}
    public record ResolvedRequirement(int taskIndex,int ordinal,int count,
            LegacyQuestDefinitionArchive.Identity legacy,String targetId,boolean ignoreNBT,boolean partialMatch) {}
    public static final class BoundQuest {
        private final String archiveSha256;
        private final Binding binding;
        private final Quest<FrozenStack> quest;
        private final List<ResolvedRequirement> requirements;
        private BoundQuest(LegacyQuestDefinitionArchive archive,Quest<FrozenStack> quest,List<ResolvedRequirement> requirements) {
            archiveSha256=archive.pin().archiveSha256();binding=archive.binding();this.quest=quest;this.requirements=List.copyOf(requirements);
        }
        public String archiveSha256(){return archiveSha256;}
        public Binding binding(){return binding;}
        public Quest<FrozenStack> quest(){return quest;}
        public List<ResolvedRequirement> requirements(){return requirements;}
    }
    public static final class Result {
        private final SourceDocument definition,settings;
        private final BoundQuest bound;
        private final List<Problem> problems;
        private Result(SourceDocument definition,SourceDocument settings,BoundQuest bound,List<Problem> problems) {
            this.definition=definition;this.settings=settings;this.bound=bound;this.problems=List.copyOf(problems);
        }
        public SourceDocument definition(){return definition;}
        public SourceDocument settings(){return settings;}
        public Optional<BoundQuest> bound(){return Optional.ofNullable(bound);}
        public List<Problem> problems(){return problems;}
        public BoundQuest requireBound(){if(bound==null)throw new IllegalArgumentException("Unresolved retrieval declaration: "+problems);return bound;}
    }
    private static final class Rejected extends RuntimeException {
        final Problem problem;
        Rejected(String path,String code){super(code);problem=new Problem(path,code);}
    }
    /** Every task and requirement is checked before returning any declaration.
     * Registry lookup has no placeholder, air or guessed-name fallback.
     */
    public static Result inspect(LegacyQuestDefinitionArchive archive,UUID id,HolderLookup.Provider registries) {
        Objects.requireNonNull(archive);Objects.requireNonNull(id);Objects.requireNonNull(registries);
        SourceDocument source=new SourceDocument(archive.questPath(id),archive.document(archive.questPath(id)));
        SourceDocument settings=new SourceDocument("QuestSettings.json",archive.document("QuestSettings.json"));
        List<Problem> problems=new ArrayList<>();List<Task<FrozenStack>> tasks=new ArrayList<>();List<ResolvedRequirement> identities=new ArrayList<>();
        Quest<FrozenStack> declaration=null;
        try {
            var parsed=archive.quest(id);need(parsed.accepted(),"$","typed_definition_rejected");
            CompoundTag root=parsed.nbt().orElseThrow();
            need(root.get("questIDHigh") instanceof LongTag && root.get("questIDLow") instanceof LongTag
                && id.equals(new UUID(root.getLong("questIDHigh"),root.getLong("questIDLow"))),"$","quest_identity_mismatch");
            CompoundTag properties=compound(compound(root,"properties","$"),"betterquesting","$.properties");
            CompoundTag config=compound(archive.settings().nbt().orElseThrow(),"betterquesting","$.settings");
            ListTag definitions=list(root,"tasks","$");Set<Integer> indexes=new HashSet<>();
            for(int ordinal=0;ordinal<definitions.size();ordinal++) {
                String path="$.tasks["+ordinal+"]";
                try {
                    CompoundTag task=(CompoundTag)definitions.get(ordinal);int index=integer(task,"index",null,path);
                    need(index>=0 && indexes.add(index),path+".index","invalid_or_duplicate_task_index");
                    String type=string(task,"taskID",null,path);need(type.equals(LegacyQuestRetrievalPlanner.RETRIEVAL_TYPE),path+".taskID","unsupported_task_type");
                    fields(task,TASK_FIELDS,path);
                    // readFromNBT overwrites all constructor flag defaults.
                    boolean ignore=flag(task,"ignoreNBT",path),partial=flag(task,"partialMatch",path),consume=flag(task,"consume",path);
                    boolean auto=flag(task,"autoConsume",path),group=flag(task,"groupDetect",path),one=flag(task,"requireOnlyOneItem",path);
                    ListTag required=list(task,"requiredItems",path);need(!required.isEmpty(),path+".requiredItems","empty_requirements");
                    int before=problems.size();
                    if(auto)problems.add(new Problem(path+".autoConsume","unsupported_auto_consume"));
                    if(group)problems.add(new Problem(path+".groupDetect","unsupported_group_detect"));
                    if(one)problems.add(new Problem(path+".requireOnlyOneItem","unsupported_any_requirement"));
                    if(consume && required.size()!=1)problems.add(new Problem(path+".requiredItems","unsupported_multi_requirement_consumption"));
                    List<Requirement<FrozenStack>> resolved=new ArrayList<>();
                    for(int item=0;item<required.size();item++) {
                        try {
                            CompoundTag requirement=(CompoundTag)required.get(item);String itemPath=path+".requiredItems["+item+"]";
                            fields(requirement,ITEM_FIELDS,itemPath);int count=integer(requirement,"Count",null,itemPath);need(count>0,itemPath+".Count","unsupported_requirement_count");
                            String old=string(requirement,"id",null,itemPath);need(!old.isEmpty(),itemPath+".id","empty_legacy_identity");
                            Tag damageTag=requirement.get("Damage");need(damageTag==null || damageTag instanceof ShortTag,itemPath+".Damage","unsupported_metadata_type");
                            int damage=requirement.getShort("Damage");need(damage!=32767,itemPath+".Damage","wildcard_metadata_set_mapping_missing");String ore=string(requirement,"OreDict","",itemPath);
                            need(ore.isEmpty(),itemPath+".OreDict","ore_set_mapping_missing");
                            CompoundTag nbt=compound(requirement,"tag",itemPath);
                            need(ignore || nbt.isEmpty(),itemPath+".tag","legacy_nbt_component_mapping_missing");
                            var identity=new LegacyQuestDefinitionArchive.Identity(old,damage);String target=archive.mappedIdentities().get(identity);
                            need(target!=null,itemPath+".id","legacy_identity_mapping_missing");ResourceLocation targetId=ResourceLocation.tryParse(target);
                            need(targetId!=null,itemPath+".id","invalid_target_identity");
                            var lookup=registries.lookup(Registries.ITEM);need(lookup.isPresent(),itemPath+".id","item_registry_unavailable");
                            var holder=lookup.orElseThrow().get(ResourceKey.create(Registries.ITEM,targetId));need(holder.isPresent(),itemPath+".id","mapped_target_not_registered");
                            ItemStack stack=holder.orElseThrow().value().getDefaultInstance();need(!stack.isEmpty(),itemPath+".id","empty_mapped_target");
                            FrozenStack frozen;
                            try {frozen=FrozenStack.of(stack,registries);}catch(IllegalArgumentException e){throw new Rejected(itemPath+".id","target_components_not_preservable");}
                            resolved.add(new Requirement<>(count,sample->matches(frozen,sample,ignore)));
                            identities.add(new ResolvedRequirement(index,item,count,identity,target,ignore,partial));
                        }catch(Rejected failure){problems.add(failure.problem);}
                    }
                    if(before==problems.size())tasks.add(Task.retrieval(index,new Config<>(resolved,consume,auto,group,one)));
                }catch(Rejected failure){problems.add(failure.problem);}
            }
            declaration=new Quest<>(id,tasks,string(properties,"taskLogic","AND","$.properties.betterquesting"),
                integer(properties,"repeatTime",-1,"$.properties.betterquesting"),flag(properties,"simultaneous","$.properties.betterquesting"),
                flag(properties,"isGlobal","$.properties.betterquesting"),flag(properties,"globalShare","$.properties.betterquesting"),flag(config,"editMode","$.settings.betterquesting"));
            for(var problem:LegacyQuestRetrievalPlanner.unsupported(declaration)) {
                if(!problem.code().equals("unsupported_empty_quest") || definitions.isEmpty())problems.add(new Problem(problem.path(),problem.code()));
            }
        }catch(Rejected failure){problems.add(failure.problem);}
        // No partial quest escapes when a later item, task or source mode fails.
        return new Result(source,settings,problems.isEmpty()?new BoundQuest(archive,Objects.requireNonNull(declaration),identities):null,problems);
    }
    private static boolean matches(FrozenStack required,FrozenStack sample,boolean ignore) {
        if(sample==null)return false;ItemStack expected=required.stack(),actual=sample.stack();
        if(ignore)return ItemStack.isSameItem(expected,actual);
        // Empty required NBT is strict even when partialMatch=true in BQ.
        return LegacyRetrievalStackMatcher.matches(expected,actual,value->ItemStack.isSameItemSameComponents(expected,value));
    }
    private static void fields(CompoundTag value,Set<String> allowed,String path){for(String key:value.getAllKeys())need(allowed.contains(key),path+"."+key,"unknown_definition_field");}
    private static boolean flag(CompoundTag tag,String key,String path){Tag value=tag.get(key);need(value==null || value instanceof ByteTag,path+"."+key,"unsupported_flag_type");return tag.getBoolean(key);}
    private static int integer(CompoundTag tag,String key,Integer fallback,String path){Tag value=tag.get(key);if(value==null && fallback!=null)return fallback;need(value instanceof IntTag,path+"."+key,"unsupported_integer_type");return tag.getInt(key);}
    private static String string(CompoundTag tag,String key,String fallback,String path){Tag value=tag.get(key);if(value==null && fallback!=null)return fallback;need(value instanceof StringTag,path+"."+key,"unsupported_string_type");return tag.getString(key);}
    private static CompoundTag compound(CompoundTag tag,String key,String path){Tag value=tag.get(key);if(value==null)return new CompoundTag();need(value instanceof CompoundTag,path+"."+key,"unsupported_compound_type");return (CompoundTag)value;}
    private static ListTag list(CompoundTag tag,String key,String path){Tag value=tag.get(key);need(value instanceof ListTag,path+"."+key,"unsupported_list_type");ListTag list=(ListTag)value;need(list.isEmpty() || list.getElementType()==Tag.TAG_COMPOUND,path+"."+key,"unsupported_list_element_type");return list;}
    private static void need(boolean value,String path,String code){if(!value)throw new Rejected(path,code);}
}

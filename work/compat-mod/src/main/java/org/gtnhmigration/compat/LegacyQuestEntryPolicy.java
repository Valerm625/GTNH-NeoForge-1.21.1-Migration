package org.gtnhmigration.compat;

import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.State;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.Binding;

/** Source prerequisite/completion projection. This is not player or party
 * authorization, a quest cache, task completion, or reward implementation. */
public final class LegacyQuestEntryPolicy {
    public enum Logic {
        AND,NAND,OR,NOR,XOR,XNOR;
        boolean result(int complete,int total) {
            return switch(this){case AND->complete>=total;case NAND->complete<total;case OR->complete>0;
                case NOR->complete==0;case XOR->complete==1;case XNOR->complete==total-1;};
        }
    }
    public record Reference(UUID questId,byte sourceVisibilityType) {}
    public record MissingReference(UUID quest,UUID parent) {}
    public record Node(UUID id,Logic logic,boolean global,int repeatTime,List<Reference> sourceReferences,
            Set<UUID> prerequisites,Map<UUID,Byte> visibilityTypes) {
        public Node {sourceReferences=List.copyOf(sourceReferences);prerequisites=Set.copyOf(prerequisites);visibilityTypes=Map.copyOf(visibilityTypes);}
    }
    public record Status(UUID quest,UUID actor,boolean unlocked,boolean complete,boolean canSubmit,
            int completedPrerequisites,int requiredPrerequisites,Set<UUID> missingPrerequisites) {
        public Status {missingPrerequisites=Set.copyOf(missingPrerequisites);}
        /** Cache presence must come from an independently implemented runtime;
         * mounting a ledger alone does not create the old player's quest cache. */
        public LegacyQuestRetrievalPlanner.Gate gate(boolean questCacheAvailable) {
            return new LegacyQuestRetrievalPlanner.Gate(questCacheAvailable,canSubmit,unlocked,complete);
        }
    }
    private final String archiveSha256;
    private final Binding binding;
    private final Map<UUID,Node> nodes;
    private final List<MissingReference> missing;
    private LegacyQuestEntryPolicy(LegacyQuestDefinitionArchive archive,Map<UUID,Node> nodes) {
        this.archiveSha256=archive.pin().archiveSha256();binding=archive.binding();this.nodes=Map.copyOf(nodes);
        List<MissingReference> missing=new ArrayList<>();
        for(Node node:nodes.values())for(UUID parent:node.prerequisites())if(!nodes.containsKey(parent))missing.add(new MissingReference(node.id(),parent));
        this.missing=List.copyOf(missing);
    }
    public static LegacyQuestEntryPolicy fromArchive(LegacyQuestDefinitionArchive archive) {
        Map<UUID,Node> nodes=new LinkedHashMap<>();
        for(UUID id:new TreeSet<>(archive.questIds())) {
            var parsed=archive.quest(id);require(parsed.accepted(),"Cannot interpret prerequisite definition: "+id);
            CompoundTag raw=parsed.nbt().orElseThrow();CompoundTag properties=compound(compound(raw,"properties"),"betterquesting");
            String logicName=string(properties,"questLogic","AND");Logic logic;
            try{logic=Logic.valueOf(logicName);}catch(IllegalArgumentException failure){throw new IllegalArgumentException("Unknown source prerequisite logic: "+id,failure);}
            boolean global=flag(properties,"isGlobal");int repeat=integer(properties,"repeatTime",-1);
            Tag tag=raw.get("preRequisites");require(tag==null || tag instanceof ListTag,"Unsupported prerequisite representation: "+id);
            ListTag list=tag==null?new ListTag():(ListTag)tag;require(list.isEmpty() || list.getElementType()==Tag.TAG_COMPOUND,"Invalid prerequisite list: "+id);
            // The pinned loader uses a Set for logic, retaining the original raw
            // list separately avoids losing duplicate edges/presentation input.
            List<Reference> references=new ArrayList<>();Set<UUID> prerequisites=new LinkedHashSet<>();Map<UUID,Byte> visibility=new LinkedHashMap<>();
            for(Tag value:list) {
                CompoundTag ref=(CompoundTag)value;
                require(Set.of("questIDHigh","questIDLow","type").containsAll(ref.getAllKeys()),"Unknown prerequisite fields: "+id);
                require(ref.get("questIDHigh") instanceof LongTag && ref.get("questIDLow") instanceof LongTag,"Unsupported prerequisite identity: "+id);
                UUID parent=new UUID(ref.getLong("questIDHigh"),ref.getLong("questIDLow"));
                require(!ref.contains("type") || ref.get("type") instanceof ByteTag,"Unsupported prerequisite visibility: "+id);
                byte type=ref.getByte("type");references.add(new Reference(parent,type));prerequisites.add(parent);
                // RequirementType.from maps unknown numeric values to NORMAL;
                // an absent field does not overwrite an earlier duplicate type.
                if(ref.contains("type")){if(type==1 || type==2)visibility.put(parent,type);else visibility.remove(parent);}
            }
            nodes.put(id,new Node(id,logic,global,repeat,references,prerequisites,visibility));
        }
        return new LegacyQuestEntryPolicy(archive,nodes);
    }
    public Map<UUID,Node> nodes(){return nodes;}
    public List<MissingReference> missingReferences(){return missing;}
    /** Completion records are queried by identity/presence, not timestamp,
     * claim state, task counters, online state or a modern team projection. */
    public View view(State state,UUID actor){return new View(state,actor);}
    public final class View {
        private final UUID actor;
        private final Set<UUID> completedByActor,completedByAnyone;
        private View(State state,UUID actor) {
            this.actor=Objects.requireNonNull(actor);Set<UUID> own=new HashSet<>(),any=new HashSet<>();
            state.questCompletions().forEach((quest,players)->{if(!players.isEmpty())any.add(quest);if(players.containsKey(actor))own.add(quest);});
            completedByActor=Set.copyOf(own);completedByAnyone=Set.copyOf(any);
        }
        public boolean isComplete(UUID id){Node node=nodes.get(id);return node!=null && (node.global()?completedByAnyone:completedByActor).contains(id);}
        public boolean isUnlocked(UUID id) {
            Node node=requireNode(id);return node.prerequisites().isEmpty() || node.logic().result(completed(node),node.prerequisites().size());
        }
        /** This branch of canSubmit is exact only for nonrepeat definitions;
         * repeat-task/ignored-task state must be implemented before extending it. */
        public Status nonRepeatingStatus(UUID id) {
            Node node=requireNode(id);require(node.repeatTime()<0,"Repeat submission policy remains unresolved");
            Set<UUID> missing=new LinkedHashSet<>();for(UUID parent:node.prerequisites())if(!nodes.containsKey(parent))missing.add(parent);
            return new Status(id,actor,isUnlocked(id),isComplete(id),!completedByActor.contains(id),completed(node),node.prerequisites().size(),missing);
        }
        public Status manualStatus(LegacyRetrievalDefinitionBinding.BoundQuest definition) {
            require(definition.archiveSha256().equals(archiveSha256) && definition.binding().equals(binding),"Detection declaration belongs to different source definitions");
            return nonRepeatingStatus(definition.quest().id());
        }
        private int completed(Node node){int result=0;for(UUID parent:node.prerequisites())if(isComplete(parent))result++;return result;}
    }
    private Node requireNode(UUID id){Node node=nodes.get(id);require(node!=null,"Unknown quest definition: "+id);return node;}
    private static CompoundTag compound(CompoundTag parent,String key){Tag value=parent.get(key);if(value==null)return new CompoundTag();require(value instanceof CompoundTag,"Invalid property container: "+key);return (CompoundTag)value;}
    private static boolean flag(CompoundTag parent,String key){require(!parent.contains(key) || parent.get(key) instanceof ByteTag,"Invalid property flag: "+key);return parent.getBoolean(key);}
    private static int integer(CompoundTag parent,String key,int fallback){if(!parent.contains(key))return fallback;require(parent.get(key) instanceof IntTag,"Invalid integer property: "+key);return parent.getInt(key);}
    private static String string(CompoundTag parent,String key,String fallback){if(!parent.contains(key))return fallback;require(parent.get(key) instanceof StringTag,"Invalid string property: "+key);return parent.getString(key);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

package org.gtnhmigration.compat;

import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyRetrievalDefinitionBinding.BoundQuest;

/** Pure projection of the source passive completion pass for fully bound,
 * nonrepeat retrieval quests. The caller supplies the actual source cache's
 * active iteration order. This does not create a cache or authorize a write.
 * Rewards, notices and events are returned as intents, never dispatched here. */
public final class LegacyQuestCompletionPlanner {
    public record Tick(boolean clientSide, boolean cachePresent, boolean editMode, int ticksExisted) {}
    public record Plan(State before, State after, List<UUID> updatedQuests,
            List<UUID> completedEventQuests, List<UUID> completionNotices,
            Set<UUID> dirtyQuests, Set<UUID> dirtyPlayers, boolean completionEventRequired,
            boolean refreshCache) {
        public Plan {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            updatedQuests=List.copyOf(updatedQuests); completedEventQuests=List.copyOf(completedEventQuests);
            completionNotices=List.copyOf(completionNotices); dirtyQuests=Set.copyOf(dirtyQuests);
            dirtyPlayers=Set.copyOf(dirtyPlayers);
        }
    }
    private record Entry(BoundQuest bound, boolean lockedProgress, boolean silent) {}
    private final LegacyQuestDefinitionArchive archive;
    private final LegacyQuestEntryPolicy policy;

    public LegacyQuestCompletionPlanner(LegacyQuestDefinitionArchive archive) {
        this.archive=Objects.requireNonNull(archive); policy=LegacyQuestEntryPolicy.fromArchive(archive);
    }

    /** Only the active-membership expression for the supplied supported subset.
     * Visibility, repeats, auto-claims, cache sync and party membership are not
     * implemented by this projection. Never treat its result as authorization. */
    public List<UUID> activeProjection(State state, UUID actor, List<BoundQuest> definitions) {
        Objects.requireNonNull(state); Objects.requireNonNull(actor);
        List<Entry> entries=preflight(state,definitions); var view=policy.view(state,actor);
        List<UUID> result=new ArrayList<>();
        for(Entry entry:entries) {
            var status=view.manualStatus(entry.bound());
            // Bound quests are nonempty and nonrepeat, so the taskless branch
            // of QuestCache.updateCache cannot be entered in this domain.
            if((status.unlocked() || entry.lockedProgress()) && status.canSubmit())
                result.add(status.quest());
        }
        return List.copyOf(result);
    }

    public Plan plan(State before, UUID actor, List<BoundQuest> activeInSourceOrder,
            Tick tick, LongSupplier wallClockMillis) {
        Objects.requireNonNull(before); Objects.requireNonNull(actor);
        Objects.requireNonNull(tick); Objects.requireNonNull(wallClockMillis);
        // Validate the entire supplied pass before invoking even the clock.
        List<Entry> entries=preflight(before,activeInSourceOrder);
        if(tick.clientSide() || !tick.cachePresent() || tick.editMode() || tick.ticksExisted()%60!=0)
            return new Plan(before,before,List.of(),List.of(),List.of(),Set.of(),Set.of(),false,false);
        State current=before;
        List<UUID> updated=new ArrayList<>(),completed=new ArrayList<>(),notices=new ArrayList<>();
        Set<UUID> dirtyQuests=new LinkedHashSet<>(),dirtyPlayers=new LinkedHashSet<>();
        for(Entry entry:entries) {
            var definition=entry.bound(); UUID quest=definition.quest().id();
            var status=policy.view(current,actor).manualStatus(definition);
            // LOCKED_PROGRESS makes a quest active, not eligible to complete.
            if(!status.unlocked())continue;
            if(status.canSubmit()) {
                updated.add(quest);
                boolean allComplete=true;
                for(var task:definition.quest().tasks()) {
                    var key=new TaskKey(quest,task.index(),task.type());
                    if(!current.tasks().getOrDefault(key,LegacyRetrievalEngine.State.empty()).completed().contains(actor))
                        allComplete=false;
                }
                if(allComplete) {
                    Map<UUID,Map<UUID,CompoundTag>> records=new LinkedHashMap<>(current.questCompletions());
                    Map<UUID,CompoundTag> players=new LinkedHashMap<>(records.getOrDefault(quest,Map.of()));
                    // Match setComplete plus the completion row emitted by
                    // writeProgressToNBT; the ledger stores serialized rows.
                    CompoundTag record=new CompoundTag();
                    record.putBoolean("claimed",false); record.putLong("timestamp",wallClockMillis.getAsLong());
                    record.putString("uuid",actor.toString());
                    players.put(actor,record); records.put(quest,players);
                    current=new State(records,current.tasks()); dirtyPlayers.add(actor);
                }
            }
            var afterStatus=policy.view(current,actor).manualStatus(definition);
            // A stale active cache can announce an already-complete quest.
            // Match that source behavior without resetting its claim/timestamp.
            if(afterStatus.complete() && !afterStatus.canSubmit()) {
                dirtyQuests.add(quest); completed.add(quest);
                if(!entry.silent())notices.add(quest);
            }
        }
        // The source posts COMPLETED even when the list is empty.
        return new Plan(before,current,updated,completed,notices,dirtyQuests,dirtyPlayers,true,!completed.isEmpty());
    }

    private List<Entry> preflight(State state,List<BoundQuest> definitions) {
        List<Entry> result=new ArrayList<>(); Set<UUID> seen=new HashSet<>();
        for(BoundQuest bound:List.copyOf(definitions)) {
            require(bound.archiveSha256().equals(archive.pin().archiveSha256()) && bound.binding().equals(archive.binding()),
                    "Completion definition belongs to another archive");
            UUID quest=bound.quest().id(); require(seen.add(quest),"Duplicate active quest");
            require(LegacyQuestRetrievalPlanner.unsupported(bound.quest()).isEmpty(),"Unsupported completion definition");
            Set<TaskKey> keys=new HashSet<>();
            for(var task:bound.quest().tasks())keys.add(new TaskKey(quest,task.index(),task.type()));
            for(TaskKey key:state.tasks().keySet())if(key.questId().equals(quest))
                require(keys.contains(key),"Saved task identity differs from bound quest");
            CompoundTag props=archive.quest(quest).nbt().orElseThrow().getCompound("properties").getCompound("betterquesting");
            result.add(new Entry(bound,flag(props,"lockedProgress"),flag(props,"isSilent")));
        }
        return List.copyOf(result);
    }
    private static boolean flag(CompoundTag props,String key) {
        require(!props.contains(key) || props.get(key) instanceof ByteTag,"Unsupported completion property: "+key);
        return props.getBoolean(key);
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}

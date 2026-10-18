package org.gtnhmigration.compat;

import java.util.*;
import java.util.function.Predicate;

/** Pure, unintegrated model of BQ 3.8.89 retrieval detection modes.
 * No game events, quest export, persistence or item/NBT mapping are installed.
 * Caller supplies exact item predicates and ordered main+armor slot snapshots;
 * modern offhand, external inventories and unreviewed predicates are excluded
 * by that caller contract. Results are immutable plans, not inventory writes.
 */
public final class LegacyRetrievalEngine {
    private LegacyRetrievalEngine() {}

    public record Requirement<K>(int count, Predicate<? super K> matches) {
        public Requirement {
            if (count <= 0) throw new IllegalArgumentException("Requirement count must be positive");
            Objects.requireNonNull(matches, "matches");
        }
    }

    /** Original BQ flag names; only the explicitly reviewed modes are accepted. */
    public record Config<K>(List<Requirement<K>> requirements, boolean consume,
            boolean autoConsume, boolean groupDetect, boolean requireOnlyOneItem) {
        public Config {
            requirements = List.copyOf(requirements);
            if (requirements.isEmpty()) throw new IllegalArgumentException("Empty requirement list");
        }
    }

    /** index is an opaque caller slot key; list order is the original scan order. */
    public record Slot<K>(int index, K item, int count) {
        public Slot {
            if (index < 0 || count < 0) throw new IllegalArgumentException("Invalid slot/count");
            if (count > 0) Objects.requireNonNull(item, "Nonempty slot item");
        }
    }

    public record State(Map<UUID, List<Integer>> progress, Set<UUID> completed) {
        public State {
            Map<UUID, List<Integer>> copy = new LinkedHashMap<>();
            progress.forEach((id, row) -> {
                Objects.requireNonNull(id, "Progress player");
                List<Integer> values = List.copyOf(row);
                if (values.stream().anyMatch(n -> n < 0)) throw new IllegalArgumentException("Negative progress");
                copy.put(id, values);
            });
            progress = Collections.unmodifiableMap(copy);
            completed = Collections.unmodifiableSet(new LinkedHashSet<>(completed));
            if (completed.contains(null)) throw new IllegalArgumentException("Null completed player");
        }
        public static State empty() { return new State(Map.of(), Set.of()); }
    }

    /** ALL members include offline players; inventory entries include online
     * members only, in the exact scan order. The actor must be online.
     */
    public record Context<K>(UUID actor, List<UUID> allMembers, Map<UUID, List<Slot<K>>> inventories) {
        public Context {
            Objects.requireNonNull(actor, "actor");
            allMembers = List.copyOf(allMembers);
            if (allMembers.isEmpty() || new HashSet<>(allMembers).size() != allMembers.size()
                    || !allMembers.contains(actor)) throw new IllegalArgumentException("Invalid participant list");
            Map<UUID, List<Slot<K>>> copy = copyInventories(inventories);
            if (!copy.containsKey(actor) || !allMembers.containsAll(copy.keySet())) {
                throw new IllegalArgumentException("Online inventories do not match participants");
            }
            inventories = copy;
        }
    }

    public record Consumption<K>(UUID player, int slotIndex, K item, int count) {}

    /** The pinned source crashes on an exhausted slot matched again during the
     * same Detector.run call. No candidate inventory or progress is published. */
    public static final class SourceConsumptionFailure extends IllegalStateException {
        private final int slotIndex,requirementIndex;
        SourceConsumptionFailure(int slotIndex,int requirementIndex) {
            super("Source retrieval would dereference an exhausted slot: "+slotIndex+" requirement "+requirementIndex);
            this.slotIndex=slotIndex;this.requirementIndex=requirementIndex;
        }
        public int slotIndex(){return slotIndex;}
        public int requirementIndex(){return requirementIndex;}
    }

    public record Result<K>(State state, Map<UUID, List<Slot<K>>> inventories,
            List<Consumption<K>> consumed, boolean progressWritten, boolean partyDirty) {
        public Result {
            Objects.requireNonNull(state, "state");
            inventories = copyInventories(inventories);
            consumed = List.copyOf(consumed);
        }
    }

    private static <K> Map<UUID, List<Slot<K>>> copyInventories(Map<UUID, List<Slot<K>>> inventories) {
        Map<UUID, List<Slot<K>>> copy = new LinkedHashMap<>();
        inventories.forEach((id, slots) -> {
            Objects.requireNonNull(id, "Inventory player");
            List<Slot<K>> values = List.copyOf(slots);
            if (values.stream().map(Slot::index).distinct().count() != values.size()) {
                throw new IllegalArgumentException("Duplicate inventory slot");
            }
            copy.put(id, values);
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Matches BQ onInventoryChange: manual-consuming tasks are not detected. */
    public static <K> Result<K> observeInventory(Config<K> config, State previous, Context<K> context) {
        if (config.consume() && !config.autoConsume()) return unchanged(previous, context);
        return detectManually(config, previous, context);
    }

    private static <K> Result<K> unchanged(State previous, Context<K> context) {
        return new Result<>(previous, context.inventories(), List.of(), false, false);
    }

    /** One task detection, not the quest-level button protocol. The caller must
     * execute separate source tasks in their original index order, passing the
     * returned inventory snapshot to the next task, before committing a plan.
     */
    public static <K> Result<K> detectManually(Config<K> config, State previous, Context<K> context) {
        if (previous.completed().contains(context.actor())) return unchanged(previous, context);
        int size = config.requirements().size();
        Map<UUID, int[]> rows = new LinkedHashMap<>();
        boolean changed = false;
        for (UUID id : context.allMembers()) {
            List<Integer> saved = previous.progress().get(id);
            int[] row = new int[size];
            // TaskRetrieval.getUsersProgress resets a changed requirement shape.
            if (saved != null && saved.size() == size) {
                for (int i = 0; i < size; i++) row[i] = saved.get(i);
            }
            if (!config.consume()) {
                for (int i = 0; i < size; i++) {
                    if (config.groupDetect()) {
                        row[i]=0;
                    } else if (row[i] != 0 && row[i] < config.requirements().get(i).count()) {
                        row[i] = 0;
                        changed = true;
                    }
                }
            }
            rows.put(id, row);
        }

        Map<UUID, List<Slot<K>>> inventories = new LinkedHashMap<>(context.inventories());
        List<Consumption<K>> consumed = new ArrayList<>();
        boolean satisfied=false;
        for (Map.Entry<UUID, List<Slot<K>>> inventory : context.inventories().entrySet()) {
            if(satisfied)break;
            if (config.consume() && !inventory.getKey().equals(context.actor())) continue;
            List<Slot<K>> updatedSlots = new ArrayList<>(inventory.getValue());
            for (int slotPosition = 0; slotPosition < updatedSlots.size(); slotPosition++) {
                if(satisfied)break;
                Slot<K> slot = updatedSlots.get(slotPosition);
                if (slot.count() == 0) continue;
                int available=slot.count();
                int[] budgets = new int[rows.size()];
                Arrays.fill(budgets, slot.count());
                requirements: for (int i = 0; i < size; i++) {
                    Requirement<K> requirement = config.requirements().get(i);
                    if (!requirement.matches().test(slot.item())) continue;
                    int memberIndex = 0;
                    for (Map.Entry<UUID, int[]> participant : rows.entrySet()) {
                        int[] row = participant.getValue();
                        int budgetIndex = memberIndex++;
                        if (row[i] >= requirement.count()) continue;
                        int remaining = requirement.count() - row[i];
                        if (config.consume()) {
                            if (participant.getKey().equals(context.actor())) {
                                // The old Detector retains the original stack
                                // reference after decrStackSize clears its slot.
                                // A later overlapping requirement can therefore
                                // dereference a null removal. Reject the whole
                                // pure plan instead of applying partial writes.
                                if(available==0)throw new SourceConsumptionFailure(slot.index(),i);
                                int removed = Math.min(remaining, available);
                                available-=removed;
                                for (int[] memberRow : rows.values()) {
                                    memberRow[i] = Math.addExact(memberRow[i], removed);
                                }
                                updatedSlots.set(slotPosition, new Slot<>(slot.index(), slot.item(), available));
                                consumed.add(new Consumption<>(context.actor(), slot.index(), slot.item(), removed));
                                changed=true;
                                if(config.requireOnlyOneItem() && row[i]>=requirement.count()){satisfied=true;break requirements;}
                            }
                        } else {
                            int credited = Math.min(remaining, budgets[budgetIndex]);
                            budgets[budgetIndex] -= credited;
                            row[i] += credited;
                            changed=true;
                            if(config.requireOnlyOneItem() && row[i]>=requirement.count()){satisfied=true;break requirements;}
                        }
                        // Original Detector marks updated even for a zero
                        // budget or a non-runner consuming progress row.
                        changed = true;
                    }
                }
            }
            inventories.put(inventory.getKey(), updatedSlots);
        }

        Map<UUID, List<Integer>> progress = new LinkedHashMap<>(previous.progress());
        if (changed) rows.forEach((id, row) -> progress.put(id, Arrays.stream(row).boxed().toList()));
        else if(!config.consume() && config.groupDetect()) {
            // Source getBulkProgress aliases existing arrays. Group reset can
            // change those rows even when setBulkProgress/dirty flags never run.
            rows.forEach((id,row)->{var saved=previous.progress().get(id);
                if(saved!=null && saved.size()==size)progress.put(id,Arrays.stream(row).boxed().toList());});
        }
        Set<UUID> completed = new LinkedHashSet<>(previous.completed());
        boolean complete = rows.values().stream().anyMatch(row -> {
            for (int i = 0; i < size; i++) {
                boolean done=row[i]>=config.requirements().get(i).count();
                if(config.requireOnlyOneItem() && done)return true;
                if(!config.requireOnlyOneItem() && !done)return false;
            }
            return !config.requireOnlyOneItem();
        });
        if (complete) completed.addAll(context.allMembers());
        return new Result<>(new State(progress, completed), inventories, consumed, changed, changed || complete);
    }
}

package org.gtnhmigration.compat;

import java.util.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;

/** Unintegrated, side-effect-free plan for the reviewed BQ quest detect loop.
 * K must be immutable, or a caller-owned deep snapshot never mutated during or
 * after planning; match predicates must be pure. Collections are copied, K is
 * not cloned. No game inventories, FTB callbacks or quest completion are written.
 */
public final class LegacyQuestRetrievalPlanner {
    public static final String RETRIEVAL_TYPE = "bq_standard:retrieval";
    private LegacyQuestRetrievalPlanner() {}

    /** Unknown types/configurations remain in the declaration and block the
     * entire plan, even if their saved actor state is already complete.
     */
    public record Task<K>(int index, String type, Config<K> retrieval, boolean ignored) {
        public Task {
            if (index < 0) throw new IllegalArgumentException("Negative task index");
            Objects.requireNonNull(type, "type");
        }
        public static <K> Task<K> retrieval(int index, Config<K> config) {
            return new Task<>(index, RETRIEVAL_TYPE, Objects.requireNonNull(config), false);
        }
    }

    /** These are explicit source settings, not inferred modern defaults.
     * The caller retains the original definition/payload and all other source
     * requirements; this record is not a quest importer or an activation permit.
     */
    public record Quest<K>(UUID id, List<Task<K>> tasks, String taskLogic,
            long repeatTime, boolean simultaneous, boolean global,
            boolean globalShare, boolean editMode) {
        public Quest {
            Objects.requireNonNull(id, "id");
            tasks = List.copyOf(tasks);
            Objects.requireNonNull(taskLogic, "taskLogic");
        }
    }

    /** A caller-verified snapshot of the existing source entry checks. It is
     * never a substitute for server authorization, party or prerequisite checks.
     */
    public record Gate(boolean questCacheAvailable, boolean canSubmit,
            boolean unlocked, boolean questAlreadyComplete) {}

    public record Problem(String path, String code) {}

    public static final class UnsupportedQuestException extends IllegalArgumentException {
        private final Quest<?> declaration;
        private final List<Problem> problems;
        private UnsupportedQuestException(Quest<?> declaration, List<Problem> problems) {
            super("Quest declaration is not supported: " + problems);
            this.declaration = declaration;
            this.problems = List.copyOf(problems);
        }
        public Quest<?> declaration() { return declaration; }
        public List<Problem> problems() { return problems; }
    }

    public record Detection<K>(int taskIndex, Result<K> result) {}

    /** before* snapshots allow a future transaction adapter to validate its
     * inputs. This is one complete calculation, not a durable transaction or a
     * commit API. A failed calculation returns no partial Plan.
     * allTasksCompleteForActor is the source AND result, not quest completion.
     * questDirty is the quest-loop signal; individual party-dirty signals remain
     * in detections. Offline task completion never implies offline quest rewards.
     */
    public record Plan<K>(UUID questId, Map<Integer, State> beforeStates,
            Map<Integer, State> afterStates, Map<UUID, List<Slot<K>>> beforeInventories,
            Map<UUID, List<Slot<K>>> afterInventories, List<Detection<K>> detections,
            List<Consumption<K>> consumed, boolean evaluated,
            boolean allTasksCompleteForActor, boolean questDirty) {
        public Plan {
            Objects.requireNonNull(questId, "questId");
            beforeStates = copyStates(beforeStates);
            afterStates = copyStates(afterStates);
            beforeInventories = copyInventories(beforeInventories);
            afterInventories = copyInventories(afterInventories);
            detections = List.copyOf(detections);
            consumed = List.copyOf(consumed);
        }
    }

    public static List<Problem> unsupported(Quest<?> quest) {
        Objects.requireNonNull(quest, "quest");
        List<Problem> problems = new ArrayList<>();
        if (!quest.taskLogic().equals("AND")) problems.add(new Problem("taskLogic", "unsupported_task_logic"));
        if (quest.repeatTime() >= 0) problems.add(new Problem("repeatTime", "unsupported_repeat"));
        if (quest.simultaneous()) problems.add(new Problem("simultaneous", "unsupported_simultaneous"));
        if (quest.global()) problems.add(new Problem("global", "unsupported_global"));
        // GLOBAL_SHARE only affects global-quest reward claims in pinned BQ.
        // Keep the source flag; nonglobal task detection must not reject it.
        if (quest.editMode()) problems.add(new Problem("editMode", "unsupported_edit_mode"));
        if (quest.tasks().isEmpty()) problems.add(new Problem("tasks", "unsupported_empty_quest"));
        Set<Integer> indices = new HashSet<>();
        for (Task<?> task : quest.tasks()) {
            String path = "tasks[" + task.index() + "]";
            if (!indices.add(task.index())) problems.add(new Problem(path, "duplicate_task_index"));
            if (task.ignored()) problems.add(new Problem(path, "unsupported_ignored_task"));
            if (!RETRIEVAL_TYPE.equals(task.type())) problems.add(new Problem(path, "unsupported_task_type"));
            if (task.retrieval() == null) problems.add(new Problem(path, "missing_retrieval_configuration"));
        }
        return List.copyOf(problems);
    }

    /** Reproduce the supported quest-level manual detect loop in TaskStorage
     * index order. A still-incomplete earlier task does not stop later tasks.
     * Preflight all declarations before invoking any predicate or task engine.
     */
    public static <K> Plan<K> plan(Quest<K> quest, Map<Integer, State> previous,
            Context<K> context, Gate gate) {
        List<Problem> problems = unsupported(quest);
        if (!problems.isEmpty()) throw new UnsupportedQuestException(quest, problems);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(gate, "gate");
        Map<Integer, State> before = new LinkedHashMap<>(copyStates(previous));
        for (Task<K> task : quest.tasks()) before.putIfAbsent(task.index(), State.empty());
        Map<Integer, State> states = new LinkedHashMap<>(before);
        if (!gate.questCacheAvailable() || gate.questAlreadyComplete() || !gate.canSubmit() || !gate.unlocked()) {
            return new Plan<>(quest.id(), before, states, context.inventories(), context.inventories(),
                    List.of(), List.of(), false, false, false);
        }
        List<Task<K>> ordered = new ArrayList<>(quest.tasks());
        ordered.sort(Comparator.comparingInt(Task::index));
        Context<K> current = context;
        List<Detection<K>> detections = new ArrayList<>();
        List<Consumption<K>> consumed = new ArrayList<>();
        int done = 0;
        boolean update = false;
        for (Task<K> task : ordered) {
            State state = states.get(task.index());
            if (!state.completed().contains(context.actor())) {
                Result<K> result = LegacyRetrievalEngine.detectManually(task.retrieval(), state, current);
                states.put(task.index(), result.state());
                current = new Context<>(context.actor(), context.allMembers(), result.inventories());
                detections.add(new Detection<>(task.index(), result));
                consumed.addAll(result.consumed());
                if (result.state().completed().contains(context.actor())) {
                    done++;
                    update = true;
                }
            } else {
                done++;
            }
        }
        boolean complete = done == ordered.size();
        return new Plan<>(quest.id(), before, states, context.inventories(), current.inventories(),
                detections, consumed, true, complete, complete || update);
    }

    private static Map<Integer, State> copyStates(Map<Integer, State> source) {
        Map<Integer, State> copy = new LinkedHashMap<>();
        source.forEach((index, state) -> {
            if (index == null || index < 0) throw new IllegalArgumentException("Invalid saved task index");
            copy.put(index, Objects.requireNonNull(state, "Saved task state"));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static <K> Map<UUID, List<Slot<K>>> copyInventories(Map<UUID, List<Slot<K>>> source) {
        Map<UUID, List<Slot<K>>> copy = new LinkedHashMap<>();
        source.forEach((id, slots) -> copy.put(Objects.requireNonNull(id), List.copyOf(slots)));
        return Collections.unmodifiableMap(copy);
    }
}

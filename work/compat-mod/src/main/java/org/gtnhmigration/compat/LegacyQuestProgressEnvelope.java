package org.gtnhmigration.compat;

import java.util.*;
import net.minecraft.nbt.*;

/** Unintegrated model for one explicitly ordered BQ progress NBT document.
 * Acceptance means a candidate was interpreted, never that a game save can run.
 * No JSON parser, file ordering, storage, task completion, reward or export hook
 * is installed. The caller supplies an independently verified definition catalog.
 */
public final class LegacyQuestProgressEnvelope {
    private static final String RETRIEVAL = "bq_standard:retrieval";
    private static final String PLACEHOLDER = "betterquesting:placeholder";
    private static final Set<String> QUEST_FIELDS = Set.of("questIDHigh", "questIDLow", "questID", "completed", "tasks");
    private static final Set<String> TASK_FIELDS = Set.of("index", "taskID", "completeUsers", "userProgress");
    private LegacyQuestProgressEnvelope() {}

    public record TaskKey(UUID questId, int index, String factoryId) {
        public TaskKey {
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(factoryId, "factoryId");
            if (index < 0 || factoryId.isEmpty()) throw new IllegalArgumentException("Invalid task identity");
        }
    }

    public record Definition(TaskKey key, int requirementCount) {
        public Definition {
            Objects.requireNonNull(key, "key");
            if (requirementCount <= 0) throw new IllegalArgumentException("Unsupported requirement count");
        }
    }

    /** A quest may exist without a retrieval task. Factory identity is checked
     * after looking up the existing integer slot, as in BQ TaskStorage.
     */
    public static final class Catalog {
        private final Set<UUID> quests;
        private final Map<UUID, Map<Integer, Definition>> definitions;
        public Catalog(Collection<UUID> quests, Collection<Definition> definitions) {
            this.quests = Set.copyOf(quests);
            Map<UUID, Map<Integer, Definition>> byQuest = new LinkedHashMap<>();
            for (Definition definition : definitions) {
                TaskKey key = definition.key();
                if (!this.quests.contains(key.questId())) throw new IllegalArgumentException("Task without quest definition");
                Definition previous = byQuest.computeIfAbsent(key.questId(), ignored -> new LinkedHashMap<>()).putIfAbsent(key.index(), definition);
                if (previous != null) throw new IllegalArgumentException("Duplicate task index in catalog");
            }
            Map<UUID, Map<Integer, Definition>> frozen = new LinkedHashMap<>();
            byQuest.forEach((quest, tasks) -> frozen.put(quest, Map.copyOf(tasks)));
            this.definitions = Map.copyOf(frozen);
        }
        public Set<UUID> quests() { return quests; }
        public Collection<Definition> definitions() {
            return definitions.values().stream().flatMap(tasks -> tasks.values().stream()).toList();
        }
        private Definition lookup(UUID quest, int index) { return definitions.getOrDefault(quest, Map.of()).get(index); }
    }

    /** Quest completion compounds are source-defined opaque records. Only their
     * UUID routing field is interpreted. All other tag types and fields survive.
     * Accessors return deep copies of NBT; engine states are already immutable.
     */
    public static final class State {
        private final Map<UUID, Map<UUID, CompoundTag>> questCompletions;
        private final Map<TaskKey, LegacyRetrievalEngine.State> tasks;
        public State(Map<UUID, Map<UUID, CompoundTag>> questCompletions,
                Map<TaskKey, LegacyRetrievalEngine.State> tasks) {
            this.questCompletions = copyCompletions(questCompletions);
            this.tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
            this.tasks.forEach((key, state) -> { Objects.requireNonNull(key); Objects.requireNonNull(state); });
        }
        public static State empty() { return new State(Map.of(), Map.of()); }
        public Map<UUID, Map<UUID, CompoundTag>> questCompletions() { return copyCompletions(questCompletions); }
        public Map<TaskKey, LegacyRetrievalEngine.State> tasks() { return tasks; }
        @Override public boolean equals(Object other) {
            return other instanceof State value && questCompletions.equals(value.questCompletions) && tasks.equals(value.tasks);
        }
        @Override public int hashCode() { return Objects.hash(questCompletions, tasks); }
    }

    private static Map<UUID, Map<UUID, CompoundTag>> copyCompletions(Map<UUID, Map<UUID, CompoundTag>> input) {
        Map<UUID, Map<UUID, CompoundTag>> result = new LinkedHashMap<>();
        input.forEach((quest, rows) -> {
            Objects.requireNonNull(quest, "Quest identity");
            Map<UUID, CompoundTag> copied = new LinkedHashMap<>();
            rows.forEach((player, value) -> copied.put(Objects.requireNonNull(player), value.copy()));
            result.put(quest, Collections.unmodifiableMap(copied));
        });
        return Collections.unmodifiableMap(result);
    }

    public record Problem(String path, String code) {}
    public static final class ReadResult {
        private final CompoundTag original;
        private final State candidate;
        private final List<Problem> problems;
        private ReadResult(CompoundTag original, State candidate, List<Problem> problems) {
            this.original = original.copy();
            this.candidate = candidate;
            this.problems = List.copyOf(problems);
        }
        public boolean accepted() { return candidate != null; }
        public Optional<State> candidate() { return Optional.ofNullable(candidate); }
        public List<Problem> problems() { return problems; }
        public CompoundTag original() { return original.copy(); }
    }

    /** Reads legacy progress NBT, with no implicit schema/version fallback.
     * merge=false only clears addressed quest completions and matched tasks;
     * absent quests/tasks remain intact. Duplicate records apply in input order.
     * Any unsupported record rejects the entire candidate, preserving the input.
     */
    public static ReadResult readLegacy(CompoundTag supplied, Catalog catalog, State previous, boolean merge) {
        Objects.requireNonNull(supplied); Objects.requireNonNull(catalog); Objects.requireNonNull(previous);
        CompoundTag original = supplied.copy();
        List<Problem> problems = new ArrayList<>();
        checkFields(original, Set.of("questProgress"), "$", problems);
        ListTag records = list(original, "questProgress", Tag.TAG_COMPOUND, "$", problems);
        Map<UUID, Map<UUID, CompoundTag>> completions = new LinkedHashMap<>(previous.questCompletions());
        Map<TaskKey, LegacyRetrievalEngine.State> tasks = new LinkedHashMap<>(previous.tasks());
        if (records != null) for (int i = 0; i < records.size(); i++) {
            String path = "$.questProgress[" + i + "]";
            CompoundTag record = records.getCompound(i);
            checkFields(record, QUEST_FIELDS, path, problems);
            UUID quest = questId(record, path, problems);
            ListTag complete = list(record, "completed", Tag.TAG_COMPOUND, path, problems);
            ListTag taskRecords = list(record, "tasks", Tag.TAG_COMPOUND, path, problems);
            if (quest == null) continue;
            if (!catalog.quests().contains(quest)) {
                problems.add(new Problem(path, "quest_definition_missing"));
                continue;
            }
            Map<UUID, CompoundTag> current = merge
                    ? new LinkedHashMap<>(completions.getOrDefault(quest, Map.of())) : new LinkedHashMap<>();
            if (complete != null) for (int n = 0; n < complete.size(); n++) {
                CompoundTag entry = complete.getCompound(n);
                UUID player = playerId(entry, path + ".completed[" + n + "]", problems);
                // Source replaces an entire completion compound for this UUID.
                if (player != null) current.put(player, entry.copy());
            }
            completions.put(quest, current);
            if (taskRecords != null) for (int n = 0; n < taskRecords.size(); n++) {
                readTask(taskRecords.getCompound(n), quest, path + ".tasks[" + n + "]", catalog, tasks, merge, problems);
            }
        }
        return new ReadResult(original, problems.isEmpty() ? new State(completions, tasks) : null, problems);
    }

    private static UUID questId(CompoundTag record, String path, List<Problem> problems) {
        boolean high = record.contains("questIDHigh"), low = record.contains("questIDLow"), legacy = record.contains("questID");
        if (high || low) {
            if (!high || !low) { problems.add(new Problem(path, "incomplete_uuid_pair")); return null; }
            if (legacy) { problems.add(new Problem(path, "ambiguous_quest_identity")); return null; }
            if (!(record.get("questIDHigh") instanceof LongTag h) || !(record.get("questIDLow") instanceof LongTag l)) {
                problems.add(new Problem(path, "expected_long_uuid_pair")); return null;
            }
            return new UUID(h.getAsLong(), l.getAsLong());
        }
        if (record.get("questID") instanceof IntTag id && id.getAsInt() >= 0) return new UUID(0, id.getAsInt());
        problems.add(new Problem(path, legacy ? "unsupported_legacy_quest_id" : "quest_identity_missing"));
        return null;
    }

    private static UUID playerId(CompoundTag entry, String path, List<Problem> problems) {
        if (!(entry.get("uuid") instanceof StringTag text)) {
            problems.add(new Problem(path + ".uuid", "expected_string_uuid")); return null;
        }
        try { return UUID.fromString(text.getAsString()); }
        catch (IllegalArgumentException e) { problems.add(new Problem(path + ".uuid", "invalid_uuid")); return null; }
    }

    private static void readTask(CompoundTag record, UUID quest, String path, Catalog catalog,
            Map<TaskKey, LegacyRetrievalEngine.State> tasks, boolean merge, List<Problem> problems) {
        checkFields(record, TASK_FIELDS, path, problems);
        if (!(record.get("index") instanceof IntTag index) || index.getAsInt() < 0) {
            problems.add(new Problem(path + ".index", "expected_nonnegative_int_index")); return;
        }
        if (!(record.get("taskID") instanceof StringTag factory)) {
            problems.add(new Problem(path + ".taskID", "expected_string_factory")); return;
        }
        if (PLACEHOLDER.equals(factory.getAsString())) {
            problems.add(new Problem(path, "placeholder_not_supported")); return;
        }
        Definition definition = catalog.lookup(quest, index.getAsInt());
        if (definition == null) { problems.add(new Problem(path, "task_definition_missing")); return; }
        if (!definition.key().factoryId().equals(factory.getAsString())) {
            problems.add(new Problem(path, "factory_mismatch")); return;
        }
        if (!RETRIEVAL.equals(factory.getAsString())) {
            problems.add(new Problem(path, "unsupported_task_factory")); return;
        }
        CompoundTag payload = record.copy();
        // Only verified identity fields are removed from the new inner copy.
        // Any unknown fields still reach the strict codec and remain blocked.
        payload.remove("index"); payload.remove("taskID");
        var result = LegacyRetrievalProgressCodec.readLegacy(payload, definition.requirementCount(),
                tasks.getOrDefault(definition.key(), LegacyRetrievalEngine.State.empty()), merge);
        if (result.active()) tasks.put(definition.key(), result.state().orElseThrow());
        else for (var problem : result.problems()) problems.add(new Problem(path + problem.path().substring(1), problem.code()));
    }

    private static void checkFields(CompoundTag record, Set<String> allowed, String path, List<Problem> problems) {
        for (String key : new TreeSet<>(record.getAllKeys())) {
            if (!allowed.contains(key)) problems.add(new Problem(path + "." + key, "unknown_field"));
        }
    }

    private static ListTag list(CompoundTag record, String key, int elementType, String path, List<Problem> problems) {
        Tag value = record.get(key);
        if (value == null) return new ListTag();
        if (!(value instanceof ListTag result) || (!result.isEmpty() && result.getElementType() != elementType)) {
            problems.add(new Problem(path + "." + key, "unexpected_list_type")); return null;
        }
        return result;
    }
}

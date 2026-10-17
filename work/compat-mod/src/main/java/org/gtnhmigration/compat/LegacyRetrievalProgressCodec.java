package org.gtnhmigration.compat;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.gtnhmigration.compat.LegacyRetrievalEngine.State;

/** Unintegrated persistence codec for the reviewed retrieval engine.
 * Reads one task's progress payload, not a world, FTB team, or task definition.
 * It never changes the supplied NBT or previous state. Unsupported documents
 * remain available as an exact NBT-tree copy and cannot expose active state.
 */
public final class LegacyRetrievalProgressCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> LEGACY_KEYS = Set.of("userProgress", "completeUsers");
    private static final Set<String> MODERN_KEYS = Set.of("schema_version", "userProgress", "completeUsers");
    private static final Set<String> ROW_KEYS = Set.of("uuid", "data");

    private LegacyRetrievalProgressCodec() {}

    /** path and code describe unsupported data, never a proposed replacement. */
    public record Problem(String path, String code) {}

    public static final class ReadResult {
        private final CompoundTag original;
        private final State state;
        private final List<Problem> problems;

        private ReadResult(CompoundTag original, State state, List<Problem> problems) {
            this.original = original.copy();
            this.state = state;
            this.problems = List.copyOf(problems);
        }

        public boolean active() { return state != null; }
        public Optional<State> state() { return Optional.ofNullable(state); }
        public List<Problem> problems() { return problems; }

        /** Copies also preserve unknown fields, tag types, duplicate rows and
         * array suffixes discarded by the source's disk-load interpretation.
         * This is NBT-tree preservation, not compressed-byte identity.
         */
        public CompoundTag original() { return original.copy(); }
    }

    public static ReadResult readLegacy(CompoundTag payload, int requirementCount, State previous, boolean merge) {
        return read(payload, requirementCount, previous, merge, false);
    }

    public static ReadResult readModern(CompoundTag payload, int requirementCount, State previous, boolean merge) {
        return read(payload, requirementCount, previous, merge, true);
    }

    private static ReadResult read(CompoundTag supplied, int requirementCount, State previous,
            boolean merge, boolean modern) {
        Objects.requireNonNull(supplied, "payload");
        Objects.requireNonNull(previous, "previous");
        CompoundTag payload = supplied.copy();
        List<Problem> problems = new ArrayList<>();
        if (requirementCount <= 0) problems.add(new Problem("$", "unsupported_requirement_count"));
        unknownKeys(payload, modern ? MODERN_KEYS : LEGACY_KEYS, "$", problems);
        if (modern) {
            if (!(payload.get("schema_version") instanceof IntTag version)) {
                problems.add(new Problem("$.schema_version", "expected_int_version"));
            } else if (version.getAsInt() != SCHEMA_VERSION) {
                problems.add(new Problem("$.schema_version", "unsupported_version"));
            }
        }
        // Missing lists have the source's empty-list meaning. A present wrong
        // type is retained and blocked instead of silently being read as empty.
        ListTag complete = typedList(payload, "completeUsers", Tag.TAG_STRING, "$", problems);
        ListTag rows = typedList(payload, "userProgress", Tag.TAG_COMPOUND, "$", problems);
        Map<UUID, List<Integer>> incoming = new LinkedHashMap<>();
        Set<UUID> completed = new LinkedHashSet<>();
        if (complete != null) {
            for (int i = 0; i < complete.size(); i++) {
                UUID id = uuid(complete.getString(i), "$.completeUsers[" + i + "]", problems);
                if (id != null) completed.add(id);
            }
        }
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                CompoundTag row = rows.getCompound(i);
                String path = "$.userProgress[" + i + "]";
                unknownKeys(row, ROW_KEYS, path, problems);
                UUID id = null;
                if (row.get("uuid") instanceof StringTag value) id = uuid(value.getAsString(), path + ".uuid", problems);
                else problems.add(new Problem(path + ".uuid", "expected_string_uuid"));
                int[] raw = progressArray(row, path, problems);
                if (raw != null && requirementCount > 0 && id != null) {
                    // TaskRetrieval.readUserProgressFromNBT truncates/pads on
                    // disk load; the live getter instead resets mismatched rows.
                    int[] loaded = Arrays.copyOf(raw, requirementCount);
                    incoming.put(id, Arrays.stream(loaded).boxed().toList());
                }
            }
        }
        if (!problems.isEmpty()) return new ReadResult(payload, null, problems);
        Map<UUID, List<Integer>> progress = new LinkedHashMap<>();
        Set<UUID> allCompleted = new LinkedHashSet<>();
        if (merge) {
            progress.putAll(previous.progress());
            allCompleted.addAll(previous.completed());
        }
        // Source merge replaces a whole row; it never takes column maxima.
        progress.putAll(incoming);
        allCompleted.addAll(completed);
        return new ReadResult(payload, new State(progress, allCompleted), List.of());
    }

    private static void unknownKeys(CompoundTag tag, Set<String> allowed, String path, List<Problem> problems) {
        for (String key : new TreeSet<>(tag.getAllKeys())) {
            if (!allowed.contains(key)) problems.add(new Problem(path + "." + key, "unknown_field"));
        }
    }

    private static ListTag typedList(CompoundTag root, String key, int type, String path, List<Problem> problems) {
        Tag raw = root.get(key);
        if (raw == null) return new ListTag();
        if (!(raw instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != type)) {
            problems.add(new Problem(path + "." + key, "unexpected_list_type"));
            return null;
        }
        return list;
    }

    private static UUID uuid(String text, String path, List<Problem> problems) {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException e) {
            problems.add(new Problem(path, "invalid_uuid"));
            return null;
        }
    }

    private static int[] progressArray(CompoundTag row, String path, List<Problem> problems) {
        Tag raw = row.get("data");
        int[] values;
        if (raw == null) values = new int[0]; // Source missing-data default.
        else if (raw instanceof IntArrayTag array) values = array.getAsIntArray().clone();
        else if (raw instanceof ListTag list && (list.isEmpty() || list.getElementType() == Tag.TAG_INT)) {
            values = new int[list.size()];
            for (int i = 0; i < values.length; i++) values[i] = list.getInt(i);
        } else {
            problems.add(new Problem(path + ".data", "expected_int_array_or_legacy_int_list"));
            return null;
        }
        // Check even a truncated suffix: unsupported data is never silently
        // discarded to make an otherwise-invalid document appear supported.
        if (Arrays.stream(values).anyMatch(n -> n < 0)) {
            problems.add(new Problem(path + ".data", "unsupported_negative_progress"));
            return null;
        }
        return values;
    }

    public static CompoundTag writeModern(State state) { return writeModern(state, null); }

    /** A selected-user payload has source snapshot/sync semantics. It must be
     * merged when applied to a larger store, not mistaken for a full save.
     * This method writes counters as stored, without normalizing their shape.
     */
    public static CompoundTag writeModern(State state, Collection<UUID> selectedUsers) {
        Objects.requireNonNull(state, "state");
        Set<UUID> selected = selectedUsers == null ? null : Set.copyOf(selectedUsers);
        CompoundTag result = new CompoundTag();
        result.putInt("schema_version", SCHEMA_VERSION);
        ListTag complete = new ListTag();
        for (UUID id : new TreeSet<>(state.completed())) {
            if (selected == null || selected.contains(id)) complete.add(StringTag.valueOf(id.toString()));
        }
        result.put("completeUsers", complete);
        ListTag rows = new ListTag();
        for (Map.Entry<UUID, List<Integer>> entry : new TreeMap<>(state.progress()).entrySet()) {
            if (selected != null && !selected.contains(entry.getKey())) continue;
            CompoundTag row = new CompoundTag();
            row.putString("uuid", entry.getKey().toString());
            row.putIntArray("data", entry.getValue().stream().mapToInt(Integer::intValue).toArray());
            rows.add(row);
        }
        result.put("userProgress", rows);
        return result;
    }
}

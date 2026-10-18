package org.gtnhmigration.compat;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;

/** Versioned independent ledger representation. Never an original BQ save replacement. */
public final class LegacyQuestProgressStoreCodec {
    public static final int VERSION = 1;
    public static final String GENESIS = "0".repeat(64);
    private LegacyQuestProgressStoreCodec() {}

    public record Binding(String definitionsSha256, String identitiesSha256) {
        public Binding { requireDigest(definitionsSha256); requireDigest(identitiesSha256); }
    }
    /** Identity is descriptive data, never a filesystem path. Invalid JSON bytes may be archived too. */
    public static final class SourceDocument {
        private final String identity;
        private final byte[] bytes;
        public SourceDocument(String identity, byte[] bytes) {
            this.identity = Objects.requireNonNull(identity);
            if (identity.isEmpty()) throw new IllegalArgumentException("Empty source identity");
            this.bytes = bytes.clone();
        }
        public String identity() { return identity; }
        public byte[] bytes() { return bytes.clone(); }
        public String sha256() { return digest(bytes); }
        @Override public boolean equals(Object other) {
            return other instanceof SourceDocument s && identity.equals(s.identity) && Arrays.equals(bytes, s.bytes);
        }
        @Override public int hashCode() { return 31 * identity.hashCode() + Arrays.hashCode(bytes); }
    }
    public record Snapshot(Binding binding, State state, List<SourceDocument> sources) {
        public Snapshot {
            Objects.requireNonNull(binding); Objects.requireNonNull(state);
            sources = List.copyOf(sources);
        }
    }
    public record Frame(long revision, String parentSha256, Snapshot snapshot) {
        public Frame {
            if (revision <= 0) throw new IllegalArgumentException("Invalid revision");
            requireDigest(parentSha256); Objects.requireNonNull(snapshot);
        }
    }
    public static final class ReadResult {
        private final CompoundTag original;
        private final Frame frame;
        private final String problem;
        private ReadResult(CompoundTag original, Frame frame, String problem) {
            this.original = original.copy(); this.frame = frame; this.problem = problem;
        }
        public boolean accepted() { return frame != null; }
        public Optional<Frame> frame() { return Optional.ofNullable(frame); }
        public Optional<String> problem() { return Optional.ofNullable(problem); }
        public CompoundTag original() { return original.copy(); }
    }

    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String catalogSha256(Catalog catalog) {
        // Length-prefix variable text so separators cannot alias a different definition catalog.
        StringBuilder text = new StringBuilder("gtnh-retrieval-catalog-v1\n");
        for (UUID quest : new TreeSet<>(catalog.quests())) text.append("q:").append(quest).append('\n');
        for (Definition definition : sortedDefinitions(catalog)) {
            TaskKey key = definition.key();
            text.append("t:").append(key.questId()).append(':').append(key.index()).append(':')
                .append(key.factoryId().length()).append(':').append(key.factoryId()).append(':')
                .append(definition.requirementCount()).append('\n');
        }
        return digest(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static List<Definition> sortedDefinitions(Catalog catalog) {
        return catalog.definitions().stream().sorted(Comparator.comparing((Definition d) -> d.key().questId())
            .thenComparingInt(d -> d.key().index()).thenComparing(d -> d.key().factoryId())).toList();
    }
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
    }
    private static void require(boolean condition, String problem) {
        if (!condition) throw new IllegalArgumentException(problem);
    }
    private static void fields(CompoundTag tag, String... allowed) {
        require(tag.getAllKeys().equals(Set.of(allowed)), "Missing or unknown store fields");
    }
    private static String string(CompoundTag tag, String key) {
        require(tag.get(key) instanceof StringTag, "Expected string: " + key);
        return tag.getString(key);
    }
    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        require(id.toString().equals(value), "Noncanonical ledger UUID");
        return id;
    }
    private static ListTag list(CompoundTag tag, String key, int elementType) {
        require(tag.get(key) instanceof ListTag, "Expected list: " + key);
        ListTag list = (ListTag) tag.get(key);
        require(list.isEmpty() || list.getElementType() == elementType, "Wrong list type: " + key);
        return list;
    }

    public static CompoundTag write(Frame frame, Catalog catalog) {
        Snapshot snapshot = frame.snapshot();
        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", VERSION); root.putLong("revision", frame.revision());
        root.putString("parent_sha256", frame.parentSha256());
        root.putString("definitions_sha256", snapshot.binding().definitionsSha256());
        root.putString("identities_sha256", snapshot.binding().identitiesSha256());
        root.putString("catalog_sha256", catalogSha256(catalog));
        ListTag sources = new ListTag();
        for (SourceDocument source : snapshot.sources()) {
            CompoundTag row = new CompoundTag(); row.putString("identity", source.identity());
            row.putString("sha256", source.sha256()); row.putByteArray("bytes", source.bytes()); sources.add(row);
        }
        root.put("sources", sources);
        ListTag quests = new ListTag();
        for (var entry : new TreeMap<>(snapshot.state().questCompletions()).entrySet()) {
            CompoundTag row = new CompoundTag(); row.putString("quest", entry.getKey().toString());
            ListTag completions = new ListTag();
            for (var completion : new TreeMap<>(entry.getValue()).entrySet()) {
                CompoundTag value = new CompoundTag(); value.putString("player", completion.getKey().toString());
                value.put("original", completion.getValue().copy()); completions.add(value);
            }
            row.put("completions", completions); quests.add(row);
        }
        root.put("quest_completions", quests);
        ListTag tasks = new ListTag();
        List<TaskKey> keys = snapshot.state().tasks().keySet().stream().sorted(Comparator.comparing(TaskKey::questId)
            .thenComparingInt(TaskKey::index).thenComparing(TaskKey::factoryId)).toList();
        for (TaskKey key : keys) {
            CompoundTag row = new CompoundTag(); row.putString("quest", key.questId().toString());
            row.putInt("index", key.index()); row.putString("factory", key.factoryId());
            row.put("progress", LegacyRetrievalProgressCodec.writeModern(snapshot.state().tasks().get(key)));
            tasks.add(row);
        }
        root.put("tasks", tasks);
        ReadResult checked = read(root, snapshot.binding(), catalog);
        require(checked.accepted(), checked.problem().orElse("Invalid snapshot"));
        require(checked.frame().orElseThrow().snapshot().state().equals(snapshot.state()), "Lossy store encoding");
        return root;
    }

    public static ReadResult read(CompoundTag supplied, Binding expected, Catalog catalog) {
        Objects.requireNonNull(supplied); Objects.requireNonNull(expected); Objects.requireNonNull(catalog);
        CompoundTag root = supplied.copy();
        try {
            fields(root, "schema_version", "revision", "parent_sha256", "definitions_sha256", "identities_sha256",
                "catalog_sha256", "sources", "quest_completions", "tasks");
            require(root.get("schema_version") instanceof IntTag && root.getInt("schema_version") == VERSION, "Unsupported schema");
            require(root.get("revision") instanceof LongTag && root.getLong("revision") > 0, "Invalid revision");
            String parent = string(root, "parent_sha256"); requireDigest(parent);
            Binding actual = new Binding(string(root, "definitions_sha256"), string(root, "identities_sha256"));
            require(actual.equals(expected), "Definition or identity mapping changed");
            require(string(root, "catalog_sha256").equals(catalogSha256(catalog)), "Catalog shape changed");
            List<SourceDocument> sources = new ArrayList<>();
            Set<String> sourceKeys = new HashSet<>();
            for (Tag tag : list(root, "sources", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) tag; fields(row, "identity", "sha256", "bytes");
                require(row.get("bytes") instanceof ByteArrayTag, "Expected original source bytes");
                SourceDocument source = new SourceDocument(string(row, "identity"), row.getByteArray("bytes"));
                require(source.sha256().equals(string(row, "sha256")), "Source digest mismatch");
                require(sourceKeys.add(source.identity().length() + ":" + source.identity() + source.sha256()), "Duplicate source archive entry");
                sources.add(source);
            }
            Map<UUID, Map<UUID, CompoundTag>> completions = new LinkedHashMap<>();
            for (Tag tag : list(root, "quest_completions", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) tag; fields(row, "quest", "completions");
                UUID quest = uuid(string(row, "quest"));
                require(catalog.quests().contains(quest) && !completions.containsKey(quest), "Unknown or duplicate quest");
                Map<UUID, CompoundTag> entries = new LinkedHashMap<>();
                for (Tag completion : list(row, "completions", Tag.TAG_COMPOUND)) {
                    CompoundTag value = (CompoundTag) completion; fields(value, "player", "original");
                    UUID player = uuid(string(value, "player"));
                    require(value.get("original") instanceof CompoundTag, "Missing original completion");
                    CompoundTag original = value.getCompound("original");
                    require(UUID.fromString(string(original, "uuid")).equals(player), "Completion routing mismatch");
                    require(entries.putIfAbsent(player, original.copy()) == null, "Duplicate completion player");
                }
                completions.put(quest, entries);
            }
            Map<TaskKey, Definition> definitions = new HashMap<>();
            catalog.definitions().forEach(d -> definitions.put(d.key(), d));
            Map<TaskKey, LegacyRetrievalEngine.State> tasks = new LinkedHashMap<>();
            for (Tag tag : list(root, "tasks", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) tag; fields(row, "quest", "index", "factory", "progress");
                require(row.get("index") instanceof IntTag, "Expected original integer task index");
                TaskKey key = new TaskKey(uuid(string(row, "quest")), row.getInt("index"), string(row, "factory"));
                Definition definition = definitions.get(key);
                require(definition != null && key.factoryId().equals("bq_standard:retrieval"), "Unknown task identity or factory");
                require(!tasks.containsKey(key), "Duplicate task identity");
                require(row.get("progress") instanceof CompoundTag, "Missing progress payload");
                CompoundTag payload = row.getCompound("progress");
                var decoded = LegacyRetrievalProgressCodec.readModern(payload, definition.requirementCount(), LegacyRetrievalEngine.State.empty(), false);
                require(decoded.active(), "Unsupported progress payload");
                LegacyRetrievalEngine.State state = decoded.state().orElseThrow();
                // The legacy importer may normalize arrays or duplicate rows. A ledger must never do so.
                require(LegacyRetrievalProgressCodec.writeModern(state).equals(payload), "Noncanonical or lossy ledger progress");
                tasks.put(key, state);
            }
            Frame frame = new Frame(root.getLong("revision"), parent, new Snapshot(actual, new State(completions, tasks), sources));
            return new ReadResult(root, frame, null);
        } catch (IllegalArgumentException e) {
            return new ReadResult(root, null, e.getMessage());
        }
    }
}

package org.gtnhmigration.compat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import static org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.GENESIS;

/** Actual NBT/file/lock/restart tests; synthetic players and no game inventory. */
public final class LegacyQuestProgressStoreCheck {
    static int assertions, rejected, roundtrips;
    static final UUID QUEST = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
    static final UUID PLAYER = new UUID(0, 5), OFFLINE = new UUID(42, 7), LATCH_ONLY = new UUID(9, 9);
    static final TaskKey KEY = new TaskKey(QUEST, 17, "bq_standard:retrieval");
    static final Catalog CATALOG = new Catalog(List.of(QUEST, new UUID(0, 2)), List.of(new Definition(KEY, 2)));
    static final Binding BINDING = new Binding("a".repeat(64), "b".repeat(64));
    static final SourceDocument RAW = new SourceDocument("QuestProgress/opaque.json", new byte[]{0, -1, 13, 10, 34, 92});
    static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    static State state(int value) {
        CompoundTag complete = new CompoundTag(); complete.putString("uuid", OFFLINE.toString());
        complete.putLong("timestamp", Long.MAX_VALUE); complete.putByteArray("opaqueBytes", new byte[]{-1, 2});
        complete.putLongArray("opaqueLongs", new long[]{Long.MIN_VALUE, 5});
        CompoundTag extra = new CompoundTag(); extra.putFloat("tiny", Float.MIN_VALUE); extra.putDouble("large", Double.MAX_VALUE);
        complete.put("futureSourceFields", extra);
        return new State(Map.of(QUEST, Map.of(OFFLINE, complete), new UUID(0, 2), Map.of()),
            Map.of(KEY, new LegacyRetrievalEngine.State(Map.of(PLAYER, List.of(value, 8), OFFLINE, List.of(4, 1)), Set.of(LATCH_ONLY))));
    }
    static Frame frame(int value) { return new Frame(1, GENESIS, new Snapshot(BINDING, state(value), List.of(RAW))); }
    static CompoundTag binary(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        roundtrips++;
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
    static void reject(CompoundTag original, Consumer<CompoundTag> mutation) {
        CompoundTag bad = original.copy(); mutation.accept(bad); CompoundTag preserved = bad.copy();
        var result = LegacyQuestProgressStoreCodec.read(bad, BINDING, CATALOG);
        check(!result.accepted() && result.frame().isEmpty() && result.problem().isPresent(), "Bad store was accepted");
        check(result.original().equals(preserved), "Rejected input not preserved");
        bad.putString("mutated", "caller"); result.original().putString("mutated", "accessor");
        check(result.original().equals(preserved), "Original NBT accessor aliases caller"); rejected++;
    }
    interface Failing { void run() throws Exception; }
    static void fails(Failing action, String name) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection: " + name);
    }
    static void codec() throws Exception {
        for (int value = 0; value < 64; value++) {
            CompoundTag encoded = LegacyQuestProgressStoreCodec.write(frame(value), CATALOG);
            var result = LegacyQuestProgressStoreCodec.read(binary(encoded), BINDING, CATALOG);
            check(result.accepted(), "Encoded state rejected");
            Snapshot restored = result.frame().orElseThrow().snapshot();
            check(restored.state().equals(state(value)), "UUID state/completion data lost");
            check(restored.sources().equals(List.of(RAW)), "Original source bytes lost");
            check(restored.state().tasks().get(KEY).completed().equals(Set.of(LATCH_ONLY)), "Independent latch changed");
            check(restored.state().tasks().get(KEY).progress().containsKey(OFFLINE), "Offline row removed");
        }
        CompoundTag original = LegacyQuestProgressStoreCodec.write(frame(3), CATALOG);
        reject(original, t -> t.putInt("schema_version", 99));
        reject(original, t -> t.putLong("schema_version", 1));
        reject(original, t -> t.remove("sources"));
        reject(original, t -> t.putString("future", "preserve"));
        reject(original, t -> t.putLong("revision", 0));
        reject(original, t -> t.putString("parent_sha256", "no"));
        reject(original, t -> t.putString("definitions_sha256", "c".repeat(64)));
        reject(original, t -> t.putString("identities_sha256", "c".repeat(64)));
        reject(original, t -> t.putString("catalog_sha256", "c".repeat(64)));
        reject(original, t -> t.getList("sources", 10).getCompound(0).putByteArray("bytes", new byte[]{3}));
        reject(original, t -> t.getList("sources", 10).getCompound(0).putInt("future", 1));
        reject(original, t -> t.getList("sources", 10).add(t.getList("sources", 10).getCompound(0).copy()));
        reject(original, t -> t.getList("quest_completions", 10).getCompound(0).putString("quest", new UUID(0, 999).toString()));
        reject(original, t -> t.getList("quest_completions", 10).add(t.getList("quest_completions", 10).getCompound(0).copy()));
        reject(original, t -> t.getList("quest_completions", 10).getCompound(0).getList("completions", 10).getCompound(0).putString("player", PLAYER.toString()));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).putInt("index", 18));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).putLong("index", 17));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).putString("factory", "unknown:task"));
        reject(original, t -> t.getList("tasks", 10).add(t.getList("tasks", 10).getCompound(0).copy()));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).getCompound("progress").putInt("future", 1));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).getCompound("progress").getList("userProgress", 10).getCompound(0).putIntArray("data", new int[]{1}));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).getCompound("progress").getList("userProgress", 10).getCompound(0).putIntArray("data", new int[]{1, 2, 3}));
        reject(original, t -> t.getList("tasks", 10).getCompound(0).getCompound("progress").getList("userProgress", 10).getCompound(0).putIntArray("data", new int[]{-1, 2}));
        reject(original, t -> { ListTag rows=t.getList("tasks",10).getCompound(0).getCompound("progress").getList("userProgress",10); rows.add(rows.getCompound(0).copy()); });
        reject(original, t -> { ListTag rows=t.getList("tasks",10).getCompound(0).getCompound("progress").getList("completeUsers",8); rows.add(rows.get(0).copy()); });
        Catalog changed = new Catalog(CATALOG.quests(), List.of(new Definition(KEY, 3)));
        check(!LegacyQuestProgressStoreCodec.read(original, BINDING, changed).accepted(), "Changed requirement catalog accepted");
        byte[] bytes=RAW.bytes(); bytes[0]=22;
        check(RAW.bytes()[0]==0, "Raw accessor not copied");
        State snapshot=state(5); snapshot.questCompletions().get(QUEST).get(OFFLINE).putInt("mutate",9);
        check(!snapshot.questCompletions().get(QUEST).get(OFFLINE).contains("mutate"), "Completion alias leaked");
        fails(() -> LegacyQuestProgressStoreCodec.write(new Frame(1, GENESIS, new Snapshot(BINDING,
            new State(Map.of(), Map.of(KEY, new LegacyRetrievalEngine.State(Map.of(PLAYER, List.of(5)), Set.of()))), List.of())), CATALOG), "wrong row shape write");
    }
    static void files(Path base) throws Exception {
        Path normal=base.resolve("normal"); String first;
        try (var store=LegacyQuestProgressFileStore.open(normal, BINDING, CATALOG)) {
            check(store.current().isEmpty(), "New store not empty");
            first=store.commit(Optional.empty(), state(3), List.of(RAW)).sha256();
            check(store.current().orElseThrow().frame().snapshot().state().equals(state(3)), "Published state wrong");
            fails(() -> LegacyQuestProgressFileStore.open(normal, BINDING, CATALOG), "second writer");
            String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
            Process concurrent=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),
                LegacyQuestProgressStoreCheck.class.getName(),"lock",normal.toString()).redirectErrorStream(true).start();
            boolean exited=concurrent.waitFor(20,TimeUnit.SECONDS);
            if(!exited) concurrent.destroyForcibly();
            check(exited && concurrent.exitValue()==0,"Concurrent lock checker failed");
            check(new String(concurrent.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("LOCK_BLOCKED"),"Other process was not denied");
            fails(() -> store.commit(Optional.empty(), state(7), List.of(RAW)), "stale request");
            fails(() -> store.commit(Optional.of(first), state(7), List.of()), "removed original source");
            check(store.current().orElseThrow().sha256().equals(first), "Rejected operation advanced state");
        }
        SourceDocument second=new SourceDocument(RAW.identity(), new byte[]{1,2,3});
        try (var store=LegacyQuestProgressFileStore.open(normal, BINDING, CATALOG)) {
            check(store.current().orElseThrow().sha256().equals(first), "Reopen lost head");
            var next=store.commit(Optional.of(first), state(8), List.of(RAW,second));
            check(next.frame().revision()==2, "Revision did not advance");
            check(next.frame().parentSha256().equals(first), "Parent digest not bound");
            fails(() -> store.commit(Optional.of(next.sha256()), state(9), List.of(second,RAW)), "archive reorder");
        }
        try (var store=LegacyQuestProgressFileStore.open(normal, BINDING, CATALOG)) {
            check(store.current().orElseThrow().frame().snapshot().sources().equals(List.of(RAW,second)), "Ordered archive lost on disk");
            check(store.current().orElseThrow().frame().snapshot().state().equals(state(8)), "Reopen lost latest state");
        }
        fails(() -> LegacyQuestProgressFileStore.open(normal, new Binding("c".repeat(64), "b".repeat(64)), CATALOG), "config mismatch");
        for (String scenario:List.of("corrupt", "gap", "unknown", "future", "fork", "trailing")) {
            Path bad=base.resolve(scenario); Files.createDirectories(bad);
            try(var paths=Files.list(normal)) { for(Path p:paths.filter(p->p.toString().endsWith(".nbt")).toList()) Files.copy(p,bad.resolve(p.getFileName())); }
            List<Path> frames;
            try(var paths=Files.list(bad)) { frames=paths.sorted().toList(); }
            if(scenario.equals("corrupt")) Files.write(frames.get(1), new byte[]{1,2,3});
            if(scenario.equals("gap")) Files.delete(frames.get(0));
            if(scenario.equals("unknown")) Files.writeString(bad.resolve("unrecognized.dat"), "KEEP");
            if(scenario.equals("future") || scenario.equals("trailing")) {
                Path last=frames.get(1); byte[] encoded=Files.readAllBytes(last);
                if(scenario.equals("future")) {
                    CompoundTag tag=NbtIo.read(new DataInputStream(new ByteArrayInputStream(encoded)));
                    tag.putInt("schema_version",99); ByteArrayOutputStream out=new ByteArrayOutputStream(); NbtIo.write(tag,new DataOutputStream(out)); encoded=out.toByteArray();
                } else { encoded=Arrays.copyOf(encoded,encoded.length+1); }
                String name="00000000000000000002-"+LegacyQuestProgressStoreCodec.digest(encoded)+".nbt";
                Files.delete(last); Files.write(bad.resolve(name),encoded);
            }
            if(scenario.equals("fork")) {
                CompoundTag tag=LegacyQuestProgressStoreCodec.write(new Frame(2,first,new Snapshot(BINDING,state(14),List.of(RAW,second))),CATALOG);
                ByteArrayOutputStream out=new ByteArrayOutputStream(); NbtIo.write(tag,new DataOutputStream(out));
                Files.write(bad.resolve("00000000000000000002-"+LegacyQuestProgressStoreCodec.digest(out.toByteArray())+".nbt"),out.toByteArray());
            }
            Map<String,byte[]> before=new TreeMap<>();
            try(var paths=Files.list(bad)) { for(Path p:paths.toList()) before.put(p.getFileName().toString(),Files.readAllBytes(p)); }
            fails(() -> LegacyQuestProgressFileStore.open(bad,BINDING,CATALOG),scenario);
            for(var entry:before.entrySet()) check(Arrays.equals(Files.readAllBytes(bad.resolve(entry.getKey())),entry.getValue()),"Rejected store rewrote evidence: "+scenario);
        }
        Path uncertain=base.resolve("uncertain");
        try(var store=new LegacyQuestProgressFileStore(uncertain,BINDING,CATALOG,point->{if(point==LegacyQuestProgressFileStore.Boundary.PUBLISHED)throw new IOException("after publish");})) {
            fails(()->store.commit(Optional.empty(),state(2),List.of(RAW)),"post-publication error");
            fails(store::current,"uncertain live instance");
        }
        try(var store=LegacyQuestProgressFileStore.open(uncertain,BINDING,CATALOG)) {
            check(store.current().orElseThrow().frame().snapshot().state().equals(state(2)),"Published data lost after exception");
        }
    }
    public static void main(String[] args) throws Exception {
        Path base=Path.of(args[1]);
        if(args[0].equals("crash")) {
            var point=LegacyQuestProgressFileStore.Boundary.valueOf(args[2]);
            try(var store=new LegacyQuestProgressFileStore(base,BINDING,CATALOG,at->{if(at==point)Runtime.getRuntime().halt(71);})) {
                store.commit(store.current().map(LegacyQuestProgressFileStore.Head::sha256),state(99),List.of(RAW));
            }
            throw new AssertionError("Crash injection not reached");
        }
        if(args[0].equals("lock")) {
            try(var store=LegacyQuestProgressFileStore.open(base,BINDING,CATALOG)) { throw new AssertionError("Concurrent process acquired lock"); }
            catch(IOException expected) { System.out.println("LOCK_BLOCKED"); return; }
        }
        if(args[0].equals("seed")) {
            try(var store=LegacyQuestProgressFileStore.open(base,BINDING,CATALOG)) { store.commit(Optional.empty(),state(1),List.of(RAW)); }
            return;
        }
        if(args[0].equals("verify")) {
            try(var store=LegacyQuestProgressFileStore.open(base,BINDING,CATALOG)) {
                var head=store.current().orElseThrow();
                check(head.frame().snapshot().state().equals(state(Integer.parseInt(args[2]))),"Process restart recovered wrong state");
                check(head.frame().snapshot().sources().equals(List.of(RAW)),"Process restart lost raw bytes");
                check(head.frame().revision()==Long.parseLong(args[3]),"Process restart recovered wrong revision");
            }
            System.out.println("RESTART_VERIFIED"); return;
        }
        codec(); files(base);
        System.out.println("{\"test\":\"versioned_uuid_progress_store\",\"passed\":true,\"assertions\":"+assertions+",\"rejected_codec_mutations\":"+rejected+",\"binary_nbt_roundtrips\":"+roundtrips+",\"actual_player_save_fixtures\":0,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false,\"inventory_transaction_verified\":false}");
    }
}

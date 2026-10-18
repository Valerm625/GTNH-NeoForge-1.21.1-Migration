package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

/** Append-only, hash-chained snapshots under an exclusive process lock.
 * No world/event registration and no player-inventory transaction is installed.
 * Publication requires same-directory ATOMIC_MOVE; there is no unsafe fallback.
 * File force + rename is not a proof of directory durability after power loss.
 */
public final class LegacyQuestProgressFileStore implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    private static final Pattern FRAME = Pattern.compile("([0-9]{20})-([0-9a-f]{64})\\.nbt");
    private static final Pattern PENDING = Pattern.compile("pending-[0-9a-f-]{36}\\.tmp");
    public record Head(String sha256, Frame frame) {}
    enum Boundary { TEMP_OPENED, TEMP_FORCED, PUBLISHED }
    @FunctionalInterface interface Observer { void reached(Boundary point) throws IOException; }
    private final Path directory;
    private final Binding binding;
    private final Catalog catalog;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Observer observer;
    private boolean closed;
    private boolean uncertain;

    public static LegacyQuestProgressFileStore open(Path directory, Binding binding, Catalog catalog) throws IOException {
        return new LegacyQuestProgressFileStore(directory, binding, catalog, point -> {});
    }
    LegacyQuestProgressFileStore(Path directory, Binding binding, Catalog catalog, Observer observer) throws IOException {
        this.binding = Objects.requireNonNull(binding); this.catalog = Objects.requireNonNull(catalog);
        this.observer = Objects.requireNonNull(observer);
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Ledger directory is not a real directory");
        Path lockPath = this.directory.resolve("ledger.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Ledger lock is not a regular file");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Ledger is already open by another process");
        } catch (IOException | RuntimeException e) {
            channel.close(); throw new IOException("Cannot acquire ledger lock", e);
        }
        this.lockChannel = channel; this.lock = acquired;
        try { current(); }
        catch (IOException | RuntimeException e) { close(); throw e; }
    }

    private void usable() throws IOException {
        if (closed || !lock.isValid()) throw new IOException("Ledger is closed");
        if (uncertain) throw new IOException("Commit outcome uncertain; close and reopen before any further operation");
    }
    /** Every committed revision is checked, not only the newest readable file.
     * A bad/gapped/forked chain blocks access instead of silently rolling back.
     * Pending temporary files are preserved but never treated as committed.
     */
    public synchronized Optional<Head> current() throws IOException {
        usable();
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Nonregular ledger entry: " + name);
                if (FRAME.matcher(name).matches()) paths.add(path);
                else if (!name.equals("ledger.lock") && !PENDING.matcher(name).matches()) throw new IOException("Unrecognized ledger entry: " + name);
            }
        }
        paths.sort(Comparator.comparing(p -> p.getFileName().toString()));
        Head previous = null;
        for (Path path : paths) {
            Matcher name = FRAME.matcher(path.getFileName().toString());
            if (!name.matches()) throw new AssertionError();
            byte[] bytes;
            long length = Files.size(path);
            if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("Invalid or oversized ledger frame");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_FRAME_BYTES + 1);
            }
            if (bytes.length != length || !digest(bytes).equals(name.group(2))) throw new IOException("Ledger frame digest/length mismatch");
            CompoundTag root;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                root = NbtIo.read(input, NbtAccounter.create(256L * 1024 * 1024));
                if (root == null || input.read() != -1) throw new IOException("Missing NBT root or trailing data");
            } catch (RuntimeException e) { throw new IOException("Malformed ledger NBT", e); }
            var decoded = LegacyQuestProgressStoreCodec.read(root, binding, catalog);
            if (!decoded.accepted()) throw new IOException("Blocked ledger: " + decoded.problem().orElseThrow());
            Frame frame = decoded.frame().orElseThrow();
            long revision = previous == null ? 1 : Math.addExact(previous.frame().revision(), 1);
            String parent = previous == null ? GENESIS : previous.sha256();
            if (!name.group(1).equals(String.format(Locale.ROOT, "%020d", revision)) || frame.revision() != revision || !frame.parentSha256().equals(parent))
                throw new IOException("Ledger chain is gapped, forked, or incorrectly linked");
            if (previous != null) requireArchivePrefix(previous.frame().snapshot().sources(), frame.snapshot().sources());
            previous = new Head(name.group(2), frame);
        }
        return Optional.ofNullable(previous);
    }

    /** expectedSha256 is absent only for a genuinely empty store. A successful
     * return means the snapshot was published; it says nothing about inventory.
     * All previous raw imports are retained in order in every successor.
     */
    public synchronized Head commit(Optional<String> expectedSha256, State state, List<SourceDocument> sources) throws IOException {
        Objects.requireNonNull(expectedSha256);
        Optional<Head> previous = current();
        if (!previous.map(Head::sha256).equals(expectedSha256)) throw new IOException("Stale ledger revision");
        if (previous.isPresent()) requireArchivePrefix(previous.orElseThrow().frame().snapshot().sources(), sources);
        long revision = previous.isEmpty() ? 1 : Math.addExact(previous.orElseThrow().frame().revision(), 1);
        String parent = previous.map(Head::sha256).orElse(GENESIS);
        Frame frame = new Frame(revision, parent, new Snapshot(binding, state, sources));
        CompoundTag root = LegacyQuestProgressStoreCodec.write(frame, catalog);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) { NbtIo.write(root, output); }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_FRAME_BYTES) throw new IOException("Ledger frame exceeds supported size; nothing was discarded");
        // Validate binary encoding too: compound copies alone cannot prove tag-value preservation.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!root.equals(NbtIo.read(input, NbtAccounter.create(256L * 1024 * 1024)))) throw new IOException("NBT binary round trip changed ledger data");
        }
        String hash = digest(bytes);
        Path target = directory.resolve(String.format(Locale.ROOT, "%020d-%s.nbt", revision, hash));
        Path temporary = directory.resolve("pending-" + UUID.randomUUID() + ".tmp");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing to replace existing ledger frame");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                observer.reached(Boundary.TEMP_OPENED);
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) file.write(data);
                file.force(true);
            }
            observer.reached(Boundary.TEMP_FORCED);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            observer.reached(Boundary.PUBLISHED);
            return new Head(hash, frame);
        } catch (IOException | RuntimeException e) {
            // Preserve all bytes and force re-read: an exception after rename may already be committed.
            uncertain = true; throw e;
        }
    }
    private static String digest(byte[] bytes) { return LegacyQuestProgressStoreCodec.digest(bytes); }
    private static final String GENESIS = LegacyQuestProgressStoreCodec.GENESIS;
    private static void requireArchivePrefix(List<SourceDocument> previous, List<SourceDocument> next) throws IOException {
        if (next.size() < previous.size() || !next.subList(0, previous.size()).equals(previous))
            throw new IOException("Original source archive cannot be removed, reordered or rewritten");
    }
    @Override public synchronized void close() throws IOException {
        if (!closed) { closed = true; try { lock.release(); } finally { lockChannel.close(); } }
    }
}

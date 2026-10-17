package org.gtnhmigration.compat;
import java.util.*;

/** Next-pre-tick rescan queue. Keys must retain stable equality while queued.
 * The active bridge uses MinecraftServer and ServerPlayer identities.
 */
public final class LegacyInventoryRescanQueue<S,P> {
    private final Map<S,LinkedHashSet<P>> pending=new HashMap<>();
    public static boolean observesSlot(boolean playerInventoryMenu,int index) {
        return playerInventoryMenu && index>=9 && index<=44;
    }
    public synchronized void schedule(S server,P player) {
        Objects.requireNonNull(server); Objects.requireNonNull(player);
        pending.computeIfAbsent(server,key->new LinkedHashSet<>()).add(player);
    }
    public synchronized List<P> drain(S server) {
        Set<P> players=pending.remove(server);
        return players==null?List.of():List.copyOf(players);
    }
    public synchronized void clear(S server) { pending.remove(server); }
}

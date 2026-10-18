package org.gtnhmigration.compat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.Catalog;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.Binding;
import org.gtnhmigration.compat.LegacyRetrievalInventoryBridge.Prepared;
import org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.*;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.*;

/** Submission protocol with a native PlayerDataStorage adapter. Not registered
 * with game events. The caller MUST exclude other saves, inventory mutations,
 * player admission/removal/death/clone and ledger writers for the entire call.
 * A RecoveryRequiredException means keep that exclusion: disconnecting normally
 * may itself save the uncertain inventory. No automatic disconnect/reload occurs.
 */
public final class LegacyRetrievalCommitCoordinator {
    private static final Set<LegacyQuestProgressFileStore> BUSY=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<LegacyQuestProgressFileStore> RECOVERY=Collections.newSetFromMap(new IdentityHashMap<>());
    private final LegacyQuestProgressFileStore store;
    private final Catalog catalog;
    private final LegacyRetrievalTransactionJournal journal;

    public record Outcome(Resolution resolution, Optional<String> attemptFailure) {}
    public static final class RecoveryRequiredException extends IOException {
        RecoveryRequiredException(Throwable cause) { super("Submission requires isolated recovery; do not save or admit this player",cause); }
    }
    // Package-private test seam; production entry always uses the native adapter.
    interface PlayerPort {
        UUID actor(); Inventory inventory(); Path directory();
        default Authority authority(){return Authority.PLAYER_FILE;}
        void assertExclusive();
        CompoundTag capture();
        void replaceMarker(CompoundTag expected,CompoundTag target)throws IOException;
        void save()throws IOException;
        default void verifySecondary(CompoundTag expected)throws IOException {}
        default Exception restoreSecondary(CompoundTag before,CompoundTag after)throws IOException {return null;}
    }
    public LegacyRetrievalCommitCoordinator(LegacyQuestProgressFileStore store,Binding binding,Catalog catalog) {
        this.store=Objects.requireNonNull(store);this.catalog=Objects.requireNonNull(catalog);
        journal=new LegacyRetrievalTransactionJournal(store,binding,catalog);
    }
    public boolean recoveryRequired() { synchronized(BUSY) { return RECOVERY.contains(store); } }

    /** assertExclusive is the owning lifecycle barrier's assertion, not a lock
     * created by this helper. authorization must recheck original BQ gates,
     * membership/configuration and authority; server-thread confinement alone
     * is not authentication. The provided storage must belong to this world.
     */
    public Outcome submit(Prepared inventory,Optional<LegacyQuestProgressFileStore.Head> plannedHead,
            ServerPlayer player,PlayerDataStorage storage,Runnable assertExclusive,BooleanSupplier authorization)throws IOException {
        return submit(inventory,plannedHead,new NativePlayerPort(player,storage,assertExclusive),authorization);
    }
    /** Explicit integrated-owner path. The barrier must cover the entire server,
     * including other players and world saves: saveEverything saves all of them.
     * This adapter is compiled/audited, not registered or game-runtime verified.
     */
    public Outcome submitIntegratedOwner(Prepared inventory,Optional<LegacyQuestProgressFileStore.Head> plannedHead,
            ServerPlayer player,PlayerDataStorage storage,Runnable assertExclusive,BooleanSupplier authorization)throws IOException {
        return submit(inventory,plannedHead,new NativeIntegratedOwnerPort(player,storage,assertExclusive),authorization);
    }
    Outcome submit(Prepared inventory,Optional<LegacyQuestProgressFileStore.Head> plannedHead,
            PlayerPort player,BooleanSupplier authorization)throws IOException {
        Objects.requireNonNull(inventory);Objects.requireNonNull(plannedHead);Objects.requireNonNull(player);Objects.requireNonNull(authorization);
        synchronized(store) {
            synchronized(BUSY) {
                require(!RECOVERY.contains(store),"Ledger requires recovery before another submission");
                require(BUSY.add(store),"Reentrant submission on this ledger");
            }
            try {
                player.assertExclusive();
                inventory.verifyReadyToSubmit();
                require(inventory.actor().equals(player.actor()) && inventory.ownsActorInventory(player.inventory()),"Actor inventory object mismatch");
                require(!LegacyRetrievalInventoryBridge.recoveryRequired(player.inventory()),"Actor inventory requires recovery");
                require(authorization.getAsBoolean(),"Submission is not authorized");
                require(store.current().equals(plannedHead),"Stale or inconsistent planning head");
                CompoundTag captured=player.capture();
                // Validate the entire plan before invoking any native save.
                LegacyRetrievalTransactionProposal.bind(inventory,plannedHead,catalog,captured);
                CompoundTag persistent=journal.proposePersistentMarker(captured);
                try {
                    return execute(inventory,plannedHead,player,authorization,captured,persistent);
                } catch(IOException|RuntimeException failure) {
                    quarantine(inventory);throw new RecoveryRequiredException(failure);
                } catch(Error failure) { quarantine(inventory);throw failure; }
            } finally { synchronized(BUSY) { BUSY.remove(store); } }
        }
    }
    private void quarantine(Prepared inventory) {
        inventory.quarantine();synchronized(BUSY) { RECOVERY.add(store); }
    }
    private Outcome execute(Prepared inventory,Optional<LegacyQuestProgressFileStore.Head> plannedHead,
            PlayerPort player,BooleanSupplier authorization,CompoundTag captured,CompoundTag persistent)throws IOException {
        // Save a verified baseline (and v1 marker promotion) before recording
        // intent. A void return is insufficient; every save is read back.
        Authority authority=Objects.requireNonNull(player.authority());
        player.replaceMarker(captured,persistent);
        Exception baselineFailure=null;
        try { player.save(); } catch(IOException|RuntimeException failure) { baselineFailure=failure; }
        var baseline=readPrimary(player,authority);
        require(sameCritical(persistent,baseline.data()) && sameCritical(persistent,player.capture()),"Native baseline save is not confirmed");
        player.verifySecondary(baseline.data());
        baseline.verifyUnchanged();player.assertExclusive();
        require(authorization.getAsBoolean(),"Submission authority changed during baseline save");
        var proposal=LegacyRetrievalTransactionProposal.bind(inventory,plannedHead,catalog,baseline.data());
        Pending pending=proposal.prepare(journal,authority);
        String preparedHead=store.current().orElseThrow().sha256();
        CompoundTag target=pending.playerDataToPersist();
        Exception attemptFailure=baselineFailure;boolean countAttempted=false;
        try {
            baseline.verifyUnchanged();player.assertExclusive();
            countAttempted=true;
            inventory.applyCountsWithoutPublishing(()->{
                player.assertExclusive();
                try { return authorization.getAsBoolean() && store.current().orElseThrow().sha256().equals(preparedHead); }
                catch(IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            player.replaceMarker(baseline.data(),target);
            require(sameCritical(target,player.capture()),"Live player differs from prepared after-state");
            player.save();
        } catch(IOException|RuntimeException failure) { if(attemptFailure!=null && attemptFailure!=failure) failure.addSuppressed(attemptFailure);attemptFailure=failure; }
        // Whether save returned, swallowed a failure, or threw after publication,
        // the actual primary file alone determines COMMIT versus ABORT.
        player.assertExclusive();
        var actual=readPrimary(player,authority);
        Decision decision=pending.decisionFor(actual.data());
        if(decision==Decision.ABORT) {
            if(countAttempted) inventory.restoreAfterUnpersistedAttempt();
            CompoundTag live=player.capture();
            // Refuse unrelated/ambiguous markers instead of overwriting them.
            require(sameMarker(live,target) || sameMarker(live,baseline.data()),"Unexpected live marker during abort");
            player.replaceMarker(live,baseline.data());
            require(sameCritical(actual.data(),player.capture()),"Live abort restoration differs from authoritative player");
            actual.verifyUnchanged();player.assertExclusive();
            Exception restoreFailure=player.restoreSecondary(baseline.data(),target);
            if(restoreFailure!=null) {
                if(attemptFailure!=null && attemptFailure!=restoreFailure)restoreFailure.addSuppressed(attemptFailure);
                attemptFailure=restoreFailure;
            }
        }
        require(sameCritical(actual.data(),player.capture()),"Recovered disk and live inventory/marker differ");
        player.verifySecondary(actual.data());
        actual.verifyUnchanged();player.assertExclusive();
        require(store.current().orElseThrow().sha256().equals(preparedHead),"Ledger changed during player save");
        Resolution resolution=journal.resolve(actual);
        require(resolution.decision()==decision,"Recovery decision changed");
        return new Outcome(resolution,attemptFailure==null?Optional.empty():Optional.of(attemptFailure.getClass().getName()+": "+attemptFailure.getMessage()));
    }
    private static LegacyPlayerDataFileReader.Snapshot readPrimary(PlayerPort player,Authority authority)throws IOException {
        require(player.authority()==authority,"Player storage authority changed during submission");
        return authority==Authority.WORLD_PLAYER
            ?LegacyPlayerDataFileReader.readWorldPlayer(player.directory(),player.actor())
            :LegacyPlayerDataFileReader.read(player.directory(),player.actor());
    }
    /** Shared production/fixture owner protocol. level.dat alone decides the
     * transaction; the cache and UUID mirror must agree before resolution. */
    abstract static class WorldPlayerPort implements PlayerPort {
        public final Authority authority(){return Authority.WORLD_PLAYER;}
        abstract Path playerDirectory();
        abstract CompoundTag cachedPlayer()throws IOException;
        abstract void savePlayers()throws IOException;
        public final void verifySecondary(CompoundTag expected)throws IOException {
            assertExclusive();
            CompoundTag cache=cachedPlayer();
            var mirror=LegacyPlayerDataFileReader.read(playerDirectory(),actor());
            require(sameCritical(expected,cache) && sameCritical(expected,mirror.data()),"Owner cache or UUID mirror differs from authoritative world player");
            mirror.verifyUnchanged();assertExclusive();
        }
        public final Exception restoreSecondary(CompoundTag before,CompoundTag after)throws IOException {
            assertExclusive();
            CompoundTag cache=cachedPlayer();
            var mirror=LegacyPlayerDataFileReader.read(playerDirectory(),actor());
            boolean cacheBefore=sameCritical(before,cache),mirrorBefore=sameCritical(before,mirror.data());
            require(cacheBefore || sameCritical(after,cache),"Unexpected owner cache during abort");
            require(mirrorBefore || sameCritical(after,mirror.data()),"Unexpected UUID mirror during owner abort");
            if(cacheBefore && mirrorBefore)return null;
            // The live inventory and marker have already been restored. Refresh
            // via the normal player-list save so both cache and mirror follow it.
            // Do not rewrite level.dat or mutate a stale cached NBT object.
            mirror.verifyUnchanged();assertExclusive();Exception failure=null;
            try {savePlayers();}catch(IOException|RuntimeException e){failure=e;}
            verifySecondary(before);
            return failure;
        }
    }
    static boolean sameCritical(CompoundTag a,CompoundTag b)throws IOException {
        require(a.get("UUID") instanceof IntArrayTag && a.getIntArray("UUID").length==4 && b.get("UUID") instanceof IntArrayTag && b.getIntArray("UUID").length==4,"Missing player identity");
        require(a.get("Inventory") instanceof ListTag && b.get("Inventory") instanceof ListTag,"Missing player inventory");
        return a.getUUID("UUID").equals(b.getUUID("UUID")) && a.get("Inventory").equals(b.get("Inventory")) && sameMarker(a,b);
    }
    private static boolean sameMarker(CompoundTag a,CompoundTag b)throws IOException {
        LegacyRetrievalTransactionJournal.readMarker(a);LegacyRetrievalTransactionJournal.readMarker(b);
        var an=a.getCompound("NeoForgeData");var bn=b.getCompound("NeoForgeData");
        return Objects.equals(an.get(MARKER_KEY),bn.get(MARKER_KEY))
            && Objects.equals(an.getCompound(PERSISTED_KEY).get(MARKER_KEY),bn.getCompound(PERSISTED_KEY).get(MARKER_KEY));
    }
    /** Only our two marker locations are touched; other mods' persistent data
     * stay live and are never replaced with a stale full-player snapshot. */
    static void replacePersistentMarker(CompoundTag current,CompoundTag expectedPlayer,CompoundTag targetPlayer)throws IOException {
        CompoundTag wrapper=new CompoundTag();wrapper.put("NeoForgeData",current.copy());
        require(sameMarker(wrapper,expectedPlayer),"Live marker changed before replacement");
        LegacyRetrievalTransactionJournal.readMarker(targetPlayer);
        var target=targetPlayer.getCompound("NeoForgeData");
        Tag legacy=target.get(MARKER_KEY);
        if(legacy==null) current.remove(MARKER_KEY); else current.put(MARKER_KEY,legacy.copy());
        CompoundTag persisted=current.getCompound(PERSISTED_KEY).copy();
        Tag marker=target.getCompound(PERSISTED_KEY).get(MARKER_KEY);
        if(marker==null) persisted.remove(MARKER_KEY); else persisted.put(MARKER_KEY,marker.copy());
        if(persisted.isEmpty() && !target.contains(PERSISTED_KEY)) current.remove(PERSISTED_KEY);
        else current.put(PERSISTED_KEY,persisted);
    }
    private static final class NativePlayerPort implements PlayerPort {
        private final ServerPlayer player;private final PlayerDataStorage storage;private final Runnable barrier;
        NativePlayerPort(ServerPlayer player,PlayerDataStorage storage,Runnable barrier) {
            this.player=Objects.requireNonNull(player);this.storage=Objects.requireNonNull(storage);this.barrier=Objects.requireNonNull(barrier);
        }
        public UUID actor(){return player.getUUID();}
        public Inventory inventory(){return player.getInventory();}
        public Path directory(){return storage.getPlayerDir().toPath();}
        public void assertExclusive(){
            if(player.getServer()==null || !player.getServer().isSameThread() || player.isRemoved()) throw new IllegalStateException("Player is not on its active server thread");
            // Native PlayerList.load may prefer WorldData.getLoadedPlayerTag
            // for the integrated owner. UUID.dat alone cannot prove its save.
            if(player.getServer().isSingleplayerOwner(player.getGameProfile()))
                throw new IllegalStateException("Integrated owner requires level.dat and player-cache coordination before submission");
            barrier.run();
        }
        public CompoundTag capture(){assertExclusive();return player.saveWithoutId(new CompoundTag()).copy();}
        public void replaceMarker(CompoundTag expected,CompoundTag target)throws IOException {assertExclusive();replacePersistentMarker(player.getPersistentData(),expected,target);}
        public void save(){assertExclusive();storage.save(player);}
    }
    private static final class NativeIntegratedOwnerPort extends WorldPlayerPort {
        private final ServerPlayer player;private final PlayerDataStorage storage;
        private final MinecraftServer server;private final Runnable barrier;private final Path world;
        NativeIntegratedOwnerPort(ServerPlayer player,PlayerDataStorage storage,Runnable barrier) {
            this.player=Objects.requireNonNull(player);this.storage=Objects.requireNonNull(storage);
            this.server=Objects.requireNonNull(player.getServer());this.barrier=Objects.requireNonNull(barrier);
            this.world=server.getWorldPath(LevelResource.ROOT);
        }
        public UUID actor(){return player.getUUID();}
        public Inventory inventory(){return player.getInventory();}
        public Path directory(){return world;}
        Path playerDirectory(){return storage.getPlayerDir().toPath();}
        public void assertExclusive(){
            if(player.getServer()!=server || !server.isSameThread() || player.isRemoved()
                    || server.isDedicatedServer() || !server.isSingleplayerOwner(player.getGameProfile())
                    || server.getPlayerList().getPlayer(actor())!=player || player.connection==null
                    || !playerDirectory().toAbsolutePath().normalize().equals(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize()))
                throw new IllegalStateException("Integrated owner, active player list, or world storage changed");
            barrier.run();
        }
        public CompoundTag capture(){assertExclusive();return player.saveWithoutId(new CompoundTag()).copy();}
        public void replaceMarker(CompoundTag expected,CompoundTag target)throws IOException {assertExclusive();replacePersistentMarker(player.getPersistentData(),expected,target);}
        CompoundTag cachedPlayer()throws IOException {
            assertExclusive();CompoundTag cached=server.getPlayerList().getSingleplayerData();
            require(cached!=null,"Integrated owner cache is missing");return cached.copy();
        }
        public void save(){assertExclusive();server.saveEverything(false,false,false);}
        void savePlayers(){assertExclusive();server.getPlayerList().saveAll();}
    }
    private static void require(boolean condition,String message)throws IOException {if(!condition)throw new IOException(message);}
}

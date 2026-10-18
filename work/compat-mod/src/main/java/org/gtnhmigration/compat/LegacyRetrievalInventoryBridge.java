package org.gtnhmigration.compat;

import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;

/** Real vanilla Inventory count bridge, not a registered game submit handler.
 * Caller must authorize the actor, supply source participant order, execute on
 * the server thread, and coordinate durable inventory/progress/reward handling.
 * This class never saves, marks dirty, broadcasts, or calls FTB completion.
 */
public final class LegacyRetrievalInventoryBridge {
    private LegacyRetrievalInventoryBridge() {}
    private static final IdentityHashMap<Inventory, Object> IN_FLIGHT = new IdentityHashMap<>();
    private static final Set<Inventory> RECOVERY_REQUIRED = Collections.newSetFromMap(new IdentityHashMap<>());
    /** An uncertain batch is never silently retried. Clearing this condition
     * requires a future verified recovery coordinator; no reset shortcut exists.
     */
    public static boolean recoveryRequired(Inventory inventory) {
        synchronized (IN_FLIGHT) { return RECOVERY_REQUIRED.contains(inventory); }
    }

    /** Encoded, count-independent identity. No mutable ItemStack or component
     * instance is retained. Every accessor decodes a fresh stack from deep NBT.
     * Transient/unsupported components which do not survive the codec block it.
     */
    public static final class FrozenStack {
        private final Tag encoded;
        private final HolderLookup.Provider registries;
        private FrozenStack(ItemStack stack, HolderLookup.Provider registries) {
            this.registries = Objects.requireNonNull(registries);
            if (stack.isEmpty()) throw new IllegalArgumentException("Cannot freeze an empty item identity");
            ItemStack normalized = stack.copyWithCount(1);
            encoded = normalized.save(registries).copy();
            ItemStack decoded = stack();
            if (!ItemStack.matches(normalized, decoded)) throw new IllegalArgumentException("Item components cannot be preserved by NBT");
            if (!decoded.save(registries).equals(encoded)) throw new IllegalArgumentException("Item encoding is unstable");
        }
        public static FrozenStack of(ItemStack stack, HolderLookup.Provider registries) { return new FrozenStack(stack, registries); }
        public ItemStack stack() {
            return ItemStack.parse(registries, encoded.copy()).orElseThrow(() -> new IllegalArgumentException("Item identity no longer decodes"));
        }
        @Override public boolean equals(Object other) { return other instanceof FrozenStack value && encoded.equals(value.encoded); }
        @Override public int hashCode() { return encoded.hashCode(); }
    }

    @FunctionalInterface interface WriteObserver { void afterWrite(int appliedSlots); }
    private record Edit(Inventory inventory, int slot, ItemStack original, int before, int after) {}

    public static Prepared prepare(Quest<FrozenStack> quest, Map<Integer, State> previous, UUID actor,
            List<UUID> allMembers, Map<UUID, Inventory> onlineInventories,
            HolderLookup.Provider registries, Gate gate) {
        return new Prepared(quest, previous, actor, allMembers, onlineInventories, registries, gate);
    }
    public static final class Prepared {
        private final Thread owner = Thread.currentThread();
        private final UUID actor;
        private final Map<UUID, Inventory> live;
        private final HolderLookup.Provider registries;
        private final Plan<FrozenStack> plan;
        private boolean attempted;
        private Prepared(Quest<FrozenStack> quest, Map<Integer, State> previous, UUID actor,
                List<UUID> allMembers, Map<UUID, Inventory> online, HolderLookup.Provider registries, Gate gate) {
            this.actor = Objects.requireNonNull(actor); this.registries = Objects.requireNonNull(registries);
            Map<UUID, Inventory> copied = new LinkedHashMap<>();
            Set<Inventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            online.forEach((player, inventory) -> {
                Objects.requireNonNull(player); Objects.requireNonNull(inventory);
                if (!seen.add(inventory)) throw new IllegalArgumentException("Same inventory assigned to multiple players");
                if (inventory.player != null && !inventory.player.getUUID().equals(player)) throw new IllegalArgumentException("Inventory owner mismatch");
                shape(inventory); copied.put(player, inventory);
            });
            live = Collections.unmodifiableMap(copied);
            Map<UUID, List<Slot<FrozenStack>>> snapshots = snapshot(live, registries);
            Context<FrozenStack> context = new Context<>(actor, allMembers, snapshots);
            plan = LegacyQuestRetrievalPlanner.plan(quest, previous, context, gate);
            validateCountPlan(plan, actor);
        }
        public Plan<FrozenStack> plan() { return plan; }
        public UUID actor() { return actor; }

        /** Compose the count plan with a native-shaped player snapshot. This
         * only proposes bytes; it does not save a player or prove persistence.
         * Exact ItemStack codec round trips prevent silently dropping unknown
         * item data. Main/armor slots must equal the planning snapshot; the
         * modern offhand is retained without entering legacy task detection.
         */
        public CompoundTag proposedPlayerData(CompoundTag beforePlayer) {
            if (!plan.evaluated()) throw new IllegalStateException("Unevaluated plan cannot propose a player mutation");
            if (!(beforePlayer.get("UUID") instanceof IntArrayTag) || beforePlayer.getIntArray("UUID").length != 4
                    || !beforePlayer.getUUID("UUID").equals(actor)) throw new IllegalArgumentException("Player snapshot owner mismatch");
            if (!(beforePlayer.get("Inventory") instanceof ListTag entries)
                    || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Player inventory is not a compound list");
            List<Slot<FrozenStack>> expected = plan.beforeInventories().get(actor);
            Set<Integer> present = new HashSet<>(); ListTag proposed = new ListTag(); int previous = -1;
            for (Tag entry : entries) {
                CompoundTag encoded = (CompoundTag) entry;
                if (!(encoded.get("Slot") instanceof ByteTag)) throw new IllegalArgumentException("Noncanonical player inventory slot");
                int nativeSlot = Byte.toUnsignedInt(encoded.getByte("Slot"));
                int slot = nativeSlot < 36 ? nativeSlot : nativeSlot >= 100 && nativeSlot < 104 ? nativeSlot - 64 : nativeSlot == 150 ? 40 : -1;
                if (slot < 0 || slot <= previous || !present.add(slot)) throw new IllegalArgumentException("Unknown, duplicate or unordered native inventory slot");
                previous = slot;
                CompoundTag identity = encoded.copy(); identity.remove("Slot");
                ItemStack decoded = ItemStack.parse(registries, identity).orElseThrow(() -> new IllegalArgumentException("Player item does not decode"));
                if (decoded.isEmpty() || !decoded.save(registries).equals(identity)) throw new IllegalArgumentException("Player item data cannot round trip exactly");
                if (slot == 40) { proposed.add(encoded.copy()); continue; }
                Slot<FrozenStack> want = expected.get(slot);
                if (decoded.getCount() != want.count() || !FrozenStack.of(decoded, registries).equals(want.item()))
                    throw new IllegalArgumentException("Player data differs from planned inventory");
                int count = plan.afterInventories().get(actor).get(slot).count();
                if (count == decoded.getCount()) proposed.add(encoded.copy());
                else if (count > 0) {
                    CompoundTag target = new CompoundTag(); target.putByte("Slot", (byte) nativeSlot);
                    proposed.add(decoded.copyWithCount(count).save(registries, target));
                }
            }
            for (int slot = 0; slot < 40; slot++) if (expected.get(slot).count() > 0 && !present.contains(slot))
                throw new IllegalArgumentException("Player data omits a planned inventory slot");
            CompoundTag afterPlayer = beforePlayer.copy(); afterPlayer.put("Inventory", proposed); return afterPlayer;
        }

        /** One-use in-memory application only. The precondition must recheck
         * configuration, ledger revision, party and source entry authorization.
         * It is a caller contract, not an authentication mechanism. All observed
         * main/armor slots of every online participant are independently checked.
         * Publication and a durable cross-file transaction remain unimplemented.
         */
        public Plan<FrozenStack> applyCountsWithoutPublishing(BooleanSupplier precondition) {
            return applyCountsWithoutPublishing(precondition, ignored -> {});
        }
        Plan<FrozenStack> applyCountsWithoutPublishing(BooleanSupplier precondition, WriteObserver observer) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Prepared inventory operation changed thread");
            if (attempted) throw new IllegalStateException("Prepared inventory operation is single use");
            attempted = true;
            if (!plan.evaluated()) throw new IllegalStateException("Quest detection gate was not satisfied");
            Objects.requireNonNull(precondition); Objects.requireNonNull(observer);
            synchronized (IN_FLIGHT) {
                for (Inventory inventory : live.values()) {
                    if (RECOVERY_REQUIRED.contains(inventory)) throw new IllegalStateException("Inventory requires explicit recovery");
                    if (IN_FLIGHT.containsKey(inventory)) throw new IllegalStateException("Inventory detection reentry");
                }
                live.values().forEach(inventory -> IN_FLIGHT.put(inventory, this));
            }
            List<Edit> applied = new ArrayList<>();
            try {
                if (!precondition.getAsBoolean()) throw new IllegalStateException("Submission precondition changed");
                requireSnapshot(plan.beforeInventories());
                if (!precondition.getAsBoolean()) throw new IllegalStateException("Submission precondition changed before write");
                // A caller-supplied check may have touched inventories; recheck after it.
                requireSnapshot(plan.beforeInventories());
                List<Edit> edits = new ArrayList<>();
                List<Slot<FrozenStack>> before = plan.beforeInventories().get(actor), after = plan.afterInventories().get(actor);
                for (int i = 0; i < 40; i++) if (before.get(i).count() != after.get(i).count()) {
                    ItemStack actual = get(live.get(actor), i);
                    edits.add(new Edit(live.get(actor), i, actual, actual.getCount(), after.get(i).count()));
                }
                for (Edit edit : edits) {
                    applied.add(edit); // Include a write whose implementation throws part way through.
                    if (edit.after() == 0) set(edit.inventory(), edit.slot(), ItemStack.EMPTY);
                    else edit.original().setCount(edit.after());
                    observer.afterWrite(applied.size());
                }
                requireSnapshot(plan.afterInventories());
                if (!precondition.getAsBoolean()) throw new IllegalStateException("Submission precondition changed after write");
                requireSnapshot(plan.afterInventories());
                return plan;
            } catch (RuntimeException | Error failure) {
                Collections.reverse(applied);
                boolean uncertain = false;
                for (Edit edit : applied) {
                    try {
                        ItemStack current = get(edit.inventory(), edit.slot());
                        if (current != edit.original() && !(edit.after() == 0 && current == ItemStack.EMPTY))
                            throw new IllegalStateException("Rollback refused to overwrite a separately replaced stack");
                        edit.original().setCount(edit.before());
                        set(edit.inventory(), edit.slot(), edit.original());
                    } catch (RuntimeException | Error rollback) {
                        uncertain = true;
                        if (rollback != failure) failure.addSuppressed(rollback);
                    }
                }
                if (!applied.isEmpty()) {
                    try { requireSnapshot(plan.beforeInventories()); }
                    catch (RuntimeException | Error changed) { uncertain = true; if (changed != failure) failure.addSuppressed(changed); }
                }
                if (uncertain) synchronized (IN_FLIGHT) { RECOVERY_REQUIRED.addAll(live.values()); }
                throw failure;
            } finally {
                synchronized (IN_FLIGHT) { live.values().forEach(inventory -> { if (IN_FLIGHT.get(inventory) == this) IN_FLIGHT.remove(inventory); }); }
            }
        }
        private void requireSnapshot(Map<UUID, List<Slot<FrozenStack>>> expected) {
            var actual = snapshot(live, registries);
            if (!actual.keySet().equals(expected.keySet())) throw new IllegalStateException("Observed participant inventories changed");
            for (UUID player : expected.keySet()) {
                var want = expected.get(player); var have = actual.get(player);
                if (want.size() != have.size()) throw new IllegalStateException("Observed slot domain changed");
                for (int i = 0; i < want.size(); i++) {
                    Slot<FrozenStack> a = want.get(i), b = have.get(i);
                    // The source planner retains a key after consuming its final item.
                    if (a.index() != b.index() || a.count() != b.count() || a.count() > 0 && !Objects.equals(a.item(), b.item()))
                        throw new IllegalStateException("Observed inventory changed");
                }
            }
        }
    }

    private static void validateCountPlan(Plan<FrozenStack> plan, UUID actor) {
        if (!plan.beforeInventories().keySet().equals(plan.afterInventories().keySet())) throw new IllegalArgumentException("Plan changed participant inventories");
        Map<Integer, Integer> consumed = new HashMap<>();
        for (Consumption<FrozenStack> entry : plan.consumed()) {
            if (!entry.player().equals(actor) || entry.count() <= 0 || entry.slotIndex() < 0 || entry.slotIndex() >= 40)
                throw new IllegalArgumentException("Plan consumes a nonactor or nonlegacy slot");
            Slot<FrozenStack> before = plan.beforeInventories().get(actor).get(entry.slotIndex());
            if (!Objects.equals(before.item(), entry.item())) throw new IllegalArgumentException("Consumption identity changed");
            consumed.merge(entry.slotIndex(), entry.count(), Math::addExact);
        }
        for (UUID player : plan.beforeInventories().keySet()) {
            var before = plan.beforeInventories().get(player); var after = plan.afterInventories().get(player);
            if (before.size() != 40 || after.size() != 40) throw new IllegalArgumentException("Plan changed slot domain");
            for (int slot = 0; slot < 40; slot++) {
                var a = before.get(slot); var b = after.get(slot);
                int expected = player.equals(actor) ? consumed.getOrDefault(slot, 0) : 0;
                if (a.index() != slot || b.index() != slot || a.count() - b.count() != expected
                        || b.count() < 0 || b.count() > 0 && !Objects.equals(a.item(), b.item()))
                    throw new IllegalArgumentException("Plan changed more than actor counts");
            }
        }
    }
    private static Map<UUID, List<Slot<FrozenStack>>> snapshot(Map<UUID, Inventory> inventories, HolderLookup.Provider registries) {
        // A shared mutable stack would let an actor count write alter another
        // slot/player or even the excluded offhand. Never count it twice or
        // mutate through that alias; preserve the live objects and reject.
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Inventory inventory : inventories.values()) {
            shape(inventory);
            for (int slot = 0; slot < 40; slot++) {
                ItemStack value = get(inventory, slot);
                if (!value.isEmpty() && !seen.add(value)) throw new IllegalArgumentException("Shared mutable stack across observed slots");
            }
        }
        for (Inventory inventory : inventories.values()) for (ItemStack offhand : inventory.offhand) {
            if (!offhand.isEmpty() && seen.contains(offhand)) throw new IllegalArgumentException("Observed stack aliases excluded offhand");
        }
        Map<UUID, List<Slot<FrozenStack>>> result = new LinkedHashMap<>();
        inventories.forEach((player, inventory) -> {
            shape(inventory); List<Slot<FrozenStack>> slots = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                ItemStack stack = get(inventory, i);
                if (stack.getCount() < 0) throw new IllegalArgumentException("Negative inventory count");
                slots.add(new Slot<>(i, stack.isEmpty() ? null : FrozenStack.of(stack, registries), stack.isEmpty() ? 0 : stack.getCount()));
            }
            result.put(player, List.copyOf(slots));
        });
        return Collections.unmodifiableMap(result);
    }
    private static void shape(Inventory inventory) {
        if (inventory.items.size() != 36 || inventory.armor.size() != 4) throw new IllegalArgumentException("Unknown inventory layout");
    }
    private static ItemStack get(Inventory inventory, int slot) { return slot < 36 ? inventory.items.get(slot) : inventory.armor.get(slot - 36); }
    private static void set(Inventory inventory, int slot, ItemStack stack) {
        if (slot < 36) inventory.items.set(slot, stack); else inventory.armor.set(slot - 36, stack);
    }
}

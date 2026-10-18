package org.gtnhmigration.compat;

import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;
import org.gtnhmigration.compat.LegacyRetrievalInventoryBridge.*;

/** Actual Inventory/ItemStack objects; no players/world, loader events or disk transaction. */
public final class LegacyRetrievalInventoryBridgeCheck {
    static int assertions;
    static int vanillaDefaults;
    static final UUID ACTOR=new UUID(0,1), OTHER=new UUID(0,2), OFFLINE=new UUID(0,3), QUEST=new UUID(0,2);
    static HolderLookup.Provider registries;
    static final Gate ALLOWED=new Gate(true,true,true,false);
    static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
    static void rejects(Runnable action,String message) {
        try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){assertions++;return;}
        throw new AssertionError("Expected rejection: "+message);
    }
    static Requirement<FrozenStack> require(Item item,int count) { return new Requirement<>(count, key->key.stack().is(item)); }
    static Task<FrozenStack> task(int index,boolean consume,Requirement<FrozenStack>... required) {
        return Task.retrieval(index,new Config<>(List.of(required),consume,false,false,false));
    }
    static Quest<FrozenStack> quest(Task<FrozenStack>... tasks) { return new Quest<>(QUEST,List.of(tasks),"AND",-1,false,false,false,false); }
    static Quest<FrozenStack> example() { return quest(task(0,false,require(Items.GRAVEL,9),require(Items.OAK_LOG,5)),task(1,true,require(Items.OAK_LOG,2))); }
    static Inventory inventory() { return new Inventory(null); }
    static Inventory exampleInventory() {
        Inventory inventory=inventory();inventory.items.set(0,new ItemStack(Items.GRAVEL,9));inventory.items.set(1,new ItemStack(Items.OAK_LOG,5));return inventory;
    }
    static Prepared prepare(Quest<FrozenStack> quest,Inventory inventory) { return LegacyRetrievalInventoryBridge.prepare(quest,Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inventory),registries,ALLOWED); }
    static void basic() {
        Inventory inventory=exampleInventory();inventory.offhand.set(0,new ItemStack(Items.OAK_LOG,64));
        ItemStack original=inventory.items.get(1);original.setPopTime(7);int times=inventory.getTimesChanged();
        var prepared=prepare(example(),inventory);
        check(prepared.plan().allTasksCompleteForActor(),"Expected both task models complete");
        check(inventory.items.get(1).getCount()==5,"Preparation changed inventory");
        check(prepared.plan().beforeInventories().get(ACTOR).size()==40,"Wrong legacy slot domain");
        var plan=prepared.applyCountsWithoutPublishing(()->true);
        check(inventory.items.get(1).getCount()==3,"Actual inventory did not consume two logs");
        check(inventory.items.get(1)==original && original.getPopTime()==7,"Count application replaced unrelated runtime state");
        check(inventory.items.get(0).getCount()==9 && inventory.offhand.get(0).getCount()==64,"Observed/nonlegacy items were consumed");
        check(inventory.getTimesChanged()==times,"Bridge published inventory too early");
        rejects(()->prepared.applyCountsWithoutPublishing(()->true),"same prepared object replay");
        var replay=LegacyRetrievalInventoryBridge.prepare(example(),plan.afterStates(),ACTOR,List.of(ACTOR),Map.of(ACTOR,inventory),registries,ALLOWED);
        check(replay.plan().consumed().isEmpty(),"Saved task latch was lost");replay.applyCountsWithoutPublishing(()->true);
        check(original.getCount()==3,"New prepared replay consumed again");
        Inventory reverse=exampleInventory();
        var backwards=prepare(quest(task(0,true,require(Items.OAK_LOG,2)),task(1,false,require(Items.GRAVEL,9),require(Items.OAK_LOG,5))),reverse);
        check(!backwards.plan().allTasksCompleteForActor(),"Task order ceased to matter");backwards.applyCountsWithoutPublishing(()->true);
        check(reverse.items.get(1).getCount()==3,"Reverse order count application failed");
        Inventory offhand=inventory();offhand.offhand.set(0,new ItemStack(Items.OAK_LOG,64));
        var excluded=prepare(quest(task(0,false,require(Items.OAK_LOG,1))),offhand);
        check(!excluded.plan().allTasksCompleteForActor(),"New offhand entered old task detection");
        Inventory armor=inventory();armor.armor.set(3,new ItemStack(Items.LEATHER_HELMET));
        var armored=prepare(quest(task(0,true,require(Items.LEATHER_HELMET,1))),armor);
        check(armored.plan().consumed().getFirst().slotIndex()==39,"Old main+armor numbering changed");
        armored.applyCountsWithoutPublishing(()->true);check(armor.armor.get(3).isEmpty(),"Full-stack armor consumption failed");
    }
    static void immutability() {
        for(Item item:BuiltInRegistries.ITEM) {
            ItemStack original=item.getDefaultInstance();if(original.isEmpty())continue;
            FrozenStack frozen=FrozenStack.of(original,registries);
            check(ItemStack.matches(original.copyWithCount(1),frozen.stack()),"Vanilla default stack changed: "+BuiltInRegistries.ITEM.getKey(item));
            vanillaDefaults++;
        }
        ItemStack source=new ItemStack(Items.IRON_PICKAXE);source.setDamageValue(8);
        CompoundTag data=new CompoundTag();CompoundTag nested=new CompoundTag();nested.putInt("value",7);data.put("nested",nested);
        source.set(DataComponents.CUSTOM_DATA,CustomData.of(data));source.set(DataComponents.CUSTOM_NAME,Component.literal("original"));
        var key=FrozenStack.of(source,registries);int hash=key.hashCode();
        source.setDamageValue(20);source.set(DataComponents.CUSTOM_NAME,Component.literal("changed"));
        check(key.stack().getDamageValue()==8,"Live damage mutation leaked into key");
        ItemStack copy=key.stack();copy.setDamageValue(99);copy.set(DataComponents.CUSTOM_DATA,CustomData.EMPTY);
        check(key.stack().get(DataComponents.CUSTOM_DATA).copyTag().getCompound("nested").getInt("value")==7,"Accessor changed raw snapshot");
        check(key.hashCode()==hash,"Snapshot hash changed");
        check(key.equals(FrozenStack.of(key.stack(),registries)),"Equivalent identities differ");
        DataComponentType<String> transientType=DataComponentType.<String>builder().networkSynchronized(ByteBufCodecs.STRING_UTF8).build();
        ItemStack unsupported=new ItemStack(Items.STICK);unsupported.set(transientType,"must not drop");
        rejects(()->FrozenStack.of(unsupported,registries),"transient component cannot be serialized losslessly");
        check(unsupported.get(transientType).equals("must not drop"),"Rejected item mutated");
    }
    static void stale() {
        for(int slot=0;slot<40;slot++) {
            Inventory inventory=exampleInventory();var pending=prepare(example(),inventory);
            if(slot<36)inventory.items.set(slot,new ItemStack(Items.DIAMOND));else inventory.armor.set(slot-36,new ItemStack(Items.LEATHER_BOOTS));
            rejects(()->pending.applyCountsWithoutPublishing(()->true),"stale observed slot "+slot);
            if(slot!=1)check(inventory.items.get(1).getCount()==5,"Stale nonconsumed slot allowed partial consumption");
        }
        Inventory damage=exampleInventory();damage.items.set(2,new ItemStack(Items.IRON_PICKAXE));
        var pending=prepare(example(),damage);damage.items.get(2).setDamageValue(1);
        rejects(()->pending.applyCountsWithoutPublishing(()->true),"exact snapshot must detect damage even when a match predicate ignores it");
        check(damage.items.get(1).getCount()==5,"Changed component consumed logs");
        Inventory alias=exampleInventory();var aliasedPlan=prepare(example(),alias);alias.offhand.set(0,alias.items.get(1));
        rejects(()->aliasedPlan.applyCountsWithoutPublishing(()->true),"new offhand alias");
        check(alias.items.get(1).getCount()==5 && alias.offhand.get(0).getCount()==5,"Aliased offhand changed through observed stack");
        Inventory duplicate=exampleInventory();duplicate.items.set(20,duplicate.items.get(1));
        rejects(()->prepare(example(),duplicate),"shared stack counted in two slots");
        Inventory changed=exampleInventory();var checkChanges=prepare(example(),changed);AtomicInteger calls=new AtomicInteger();
        rejects(()->checkChanges.applyCountsWithoutPublishing(()->{if(calls.incrementAndGet()==2)changed.items.set(3,new ItemStack(Items.STICK));return true;}),"precondition mutated inventory");
        check(changed.items.get(1).getCount()==5,"Precondition mutation was not rechecked");
        Inventory offhand=exampleInventory();var ignores=prepare(example(),offhand);offhand.offhand.set(0,new ItemStack(Items.DIAMOND));
        ignores.applyCountsWithoutPublishing(()->true);check(offhand.offhand.get(0).is(Items.DIAMOND),"Offhand change must neither invalidate nor enter detection");
        Inventory blocked=exampleInventory();var denied=prepare(example(),blocked);
        rejects(()->denied.applyCountsWithoutPublishing(()->false),"revoked definition/party/revision gate");
        check(blocked.items.get(1).getCount()==5,"Denied operation consumed items");
    }
    static void parties() {
        Inventory actor=inventory(),other=inventory();actor.items.set(0,new ItemStack(Items.OAK_LOG,2));other.items.set(0,new ItemStack(Items.OAK_LOG,8));
        var quest=quest(task(0,false,require(Items.OAK_LOG,10)),task(1,true,require(Items.OAK_LOG,2)));
        LinkedHashMap<UUID,Inventory> online=new LinkedHashMap<>();online.put(OTHER,other);online.put(ACTOR,actor);
        var previous=Map.of(0,new State(Map.of(OFFLINE,List.of(3)),Set.of()));
        var prepared=LegacyRetrievalInventoryBridge.prepare(quest,previous,ACTOR,List.of(OTHER,OFFLINE,ACTOR),online,registries,ALLOWED);
        check(prepared.plan().afterStates().get(0).progress().containsKey(OFFLINE),"Offline state disappeared");
        check(new ArrayList<>(prepared.plan().beforeInventories().keySet()).equals(List.of(OTHER,ACTOR)),"Online scan order changed");
        prepared.applyCountsWithoutPublishing(()->true);
        check(actor.items.get(0).isEmpty() && other.items.get(0).getCount()==8,"Manual consumption crossed actor boundary");
        Inventory first=exampleInventory(),second=inventory();online.clear();online.put(ACTOR,first);online.put(OTHER,second);
        var stale=LegacyRetrievalInventoryBridge.prepare(example(),Map.of(),ACTOR,List.of(ACTOR,OTHER),online,registries,ALLOWED);
        second.items.set(30,new ItemStack(Items.STICK));
        rejects(()->stale.applyCountsWithoutPublishing(()->true),"other participant changed");
        check(first.items.get(1).getCount()==5,"Other participant stale snapshot consumed actor items");
        rejects(()->LegacyRetrievalInventoryBridge.prepare(example(),Map.of(),ACTOR,List.of(ACTOR,OTHER),Map.of(ACTOR,first,OTHER,first),registries,ALLOWED),"shared inventory alias");
    }
    static void failures() throws Exception {
        Quest<FrozenStack> split=quest(task(0,true,require(Items.OAK_LOG,3)));
        for(int failAfter=1;failAfter<=2;failAfter++) {
            Inventory inventory=inventory();ItemStack first=new ItemStack(Items.OAK_LOG,2),second=new ItemStack(Items.OAK_LOG,2);
            inventory.items.set(0,first);inventory.items.set(1,second);var prepared=prepare(split,inventory);int boundary=failAfter;
            rejects(()->prepared.applyCountsWithoutPublishing(()->true,n->{if(n==boundary)throw new IllegalStateException("injected write failure");}),"write rollback");
            check(inventory.items.get(0)==first && first.getCount()==2 && inventory.items.get(1)==second && second.getCount()==2,"Failed batch did not restore exact original stacks/counts");
            prepare(split,inventory).applyCountsWithoutPublishing(()->true);
            check(inventory.items.get(0).isEmpty() && second.getCount()==1,"Rollback did not release inventory lease");
        }
        Inventory inventory=exampleInventory();var first=prepare(example(),inventory);var nested=prepare(example(),inventory);AtomicBoolean reentry=new AtomicBoolean();
        first.applyCountsWithoutPublishing(()->true,n->{rejects(()->nested.applyCountsWithoutPublishing(()->true),"nested detection");reentry.set(true);});
        check(reentry.get() && inventory.items.get(1).getCount()==3,"Reentrant plan consumed twice");
        Inventory afterWrite=exampleInventory();var rollback=prepare(example(),afterWrite);AtomicInteger checks=new AtomicInteger();
        rejects(()->rollback.applyCountsWithoutPublishing(()->checks.incrementAndGet()<3),"post-write precondition invalidation");
        check(afterWrite.items.get(1).getCount()==5,"Revoked post-write gate did not restore counts");
        Inventory threaded=exampleInventory();var confined=prepare(example(),threaded);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread thread=new Thread(()->{try{confined.applyCountsWithoutPublishing(()->true);}catch(Throwable problem){error.set(problem);}});thread.start();thread.join();
        check(error.get() instanceof IllegalStateException && threaded.items.get(1).getCount()==5,"Off-thread operation accepted");
        var gated=LegacyRetrievalInventoryBridge.prepare(example(),Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,threaded),registries,new Gate(true,false,true,false));
        rejects(()->gated.applyCountsWithoutPublishing(()->true),"unevaluated quest plan");
        Inventory uncertain=exampleInventory();var unsafe=prepare(example(),uncertain);ItemStack replacement=new ItemStack(Items.DIAMOND,3);
        try {
            unsafe.applyCountsWithoutPublishing(()->true,n->{uncertain.items.set(1,replacement);throw new IllegalStateException("external replacement");});
            throw new AssertionError("External replacement was not rejected");
        } catch(IllegalStateException expected) { check(expected.getSuppressed().length>0,"Unrecoverable rollback was not reported"); }
        check(uncertain.items.get(1)==replacement && replacement.getCount()==3,"Rollback destroyed a separate replacement");
        check(LegacyRetrievalInventoryBridge.recoveryRequired(uncertain),"Uncertain inventory was not quarantined");
        rejects(()->prepare(example(),uncertain).applyCountsWithoutPublishing(()->true),"uncertain inventory reused");
        Inventory errorCase=exampleInventory();var errorPlan=prepare(example(),errorCase);
        try { errorPlan.applyCountsWithoutPublishing(()->true,n->{throw new AssertionError("injected error");});throw new IllegalStateException("Error was swallowed"); }
        catch(AssertionError expected) { check(errorCase.items.get(1).getCount()==5,"Error bypassed rollback"); }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        basic();immutability();stale();parties();failures();
        System.out.println("{\"test\":\"actual_minecraft_inventory_bridge\",\"passed\":true,\"assertions\":"+assertions+",\"vanilla_default_stacks_roundtripped\":"+vanillaDefaults+",\"legacy_slots_checked\":40,\"actual_inventory_objects\":true,\"actual_itemstack_objects\":true,\"world_started\":false,\"runtime_integrated\":false,\"export_enabled\":false,\"inventory_transaction_verified\":false}");
    }
}

import java.util.function.Predicate;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem.ComponentMatchType;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Unbreakable;
import org.gtnhmigration.compat.LegacyRetrievalStackMatcher;
import org.gtnhmigration.compat.LegacyCraftingStackMatcher;

/** Actual vanilla 1.21.1 ItemStacks and pinned FTB matcher, outside a game world.
 * Source oracle: BQ 3.8.89 ItemComparison.StackMatch ignores item durability;
 * CompareNBTTag rejects named/nonempty-NBT samples when required NBT is empty.
 */
public class LegacyRetrievalStackMatcherCheck {
    private static int checks;

    static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static Predicate<ItemStack> strict(ItemStack required) {
        return sample -> ItemMatchingSystem.INSTANCE.doesItemMatch(required, sample, ComponentMatchType.STRICT, null);
    }

    static boolean matches(ItemStack required, ItemStack sample) {
        return LegacyRetrievalStackMatcher.matches(required, sample, strict(required));
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int damageableItems = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack required = new ItemStack(item);
            if (!required.isDamageableItem()) continue;
            damageableItems++;
            ItemStack sample = required.copy();
            sample.setDamageValue(Math.min(7, sample.getMaxDamage()));
            int initialDamage = sample.getDamageValue();
            require(initialDamage > 0, "Damageable item without positive damage: " + item);
            require(!strict(required).test(sample), "Original matcher no longer exposes durability regression: " + item);
            require(matches(required, sample), "Legacy durability matching rejected: " + item);
            require(sample.getDamageValue() == initialDamage, "Matcher mutated inventory item: " + item);
            require(required.getDamageValue() == 0, "Matcher mutated requirement: " + item);
            sample.set(DataComponents.CUSTOM_NAME, Component.literal("Named tool"));
            require(!matches(required, sample), "Named tool accepted for empty legacy NBT: " + item);
        }
        require(damageableItems > 30, "Expected full vanilla damageable-item registry");

        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        ItemStack sample = pickaxe.copy();
        sample.setDamageValue(7);
        CompoundTag legacyNbt = new CompoundTag();
        legacyNbt.putInt("legacy_extra", 1);
        sample.set(DataComponents.CUSTOM_DATA, CustomData.of(legacyNbt));
        require(!matches(pickaxe, sample), "Extra custom NBT was ignored");
        sample.remove(DataComponents.CUSTOM_DATA);
        sample.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        require(!matches(pickaxe, sample), "Unbreakable component was ignored");
        require(!matches(pickaxe, new ItemStack(Items.GOLDEN_PICKAXE)), "Different item identity was accepted");
        require(!matches(pickaxe, ItemStack.EMPTY), "Empty stack was accepted");

        ItemStack dirt = new ItemStack(Items.DIRT);
        ItemStack damagedDirt = dirt.copy();
        damagedDirt.set(DataComponents.DAMAGE, 7);
        require(!matches(dirt, damagedDirt), "Non-damageable item components were relaxed");
        require(matches(dirt, new ItemStack(Items.DIRT, 8)), "Active dirt task stopped matching ordinary dirt");
        ItemStack namedDirt = dirt.copy();
        namedDirt.set(DataComponents.CUSTOM_NAME, Component.literal("Named dirt"));
        require(!matches(dirt, namedDirt), "Active dirt task accepted named dirt");

        ItemStack damagedPickaxe = pickaxe.copy();
        damagedPickaxe.setDamageValue(12);
        require(!LegacyRetrievalStackMatcher.matches(pickaxe, damagedPickaxe, stack -> false), "Original predicate was bypassed");
        require(damagedPickaxe.getDamageValue() == 12, "Predicate failure mutated inventory");

        // The source Monster Hunter quest uses partialMatch=true, ignoreNBT=false
        // and a wooden sword with no NBT. FTB FUZZY broadens this predicate.
        ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
        // BQ ItemComparison.isEmptyNBT treats absent and empty compound NBT
        // identically. An explicit empty custom_data component must not add a
        // restriction, even though vanilla's component-map equality sees it.
        ItemStack emptyNbtSword = sword.copy();
        emptyNbtSword.setDamageValue(10);
        emptyNbtSword.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
        require(!strict(sword).test(emptyNbtSword), "FTB strict no longer exposes explicit-empty-NBT regression");
        require(matches(sword, emptyNbtSword), "Retrieval rejected semantically empty legacy NBT");
        require(LegacyCraftingStackMatcher.override(true, sword, emptyNbtSword, ComponentMatchType.STRICT, null),
                "Crafting rejected semantically empty legacy NBT");
        require(emptyNbtSword.has(DataComponents.CUSTOM_DATA) && emptyNbtSword.get(DataComponents.CUSTOM_DATA).isEmpty()
                        && emptyNbtSword.getDamageValue() == 10,
                "Empty-NBT normalization mutated the inventory/event stack");
        require(!sword.has(DataComponents.CUSTOM_DATA) && sword.getDamageValue() == 0,
                "Empty-NBT normalization mutated the requirement");
        ItemStack emptyNbtDirt = dirt.copy();
        emptyNbtDirt.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
        require(matches(dirt, emptyNbtDirt), "Non-damageable item rejected empty legacy NBT");
        require(emptyNbtDirt.has(DataComponents.CUSTOM_DATA), "Non-damageable inventory stack was mutated");
        require(matches(emptyNbtDirt, dirt), "Explicit-empty required NBT rejected absent sample NBT");
        require(emptyNbtDirt.has(DataComponents.CUSTOM_DATA) && !dirt.has(DataComponents.CUSTOM_DATA),
                "Reverse empty-NBT matching mutated requirement or sample");
        ItemStack namedEmptyNbtSword = emptyNbtSword.copy();
        namedEmptyNbtSword.set(DataComponents.CUSTOM_NAME, Component.literal("Still named"));
        require(!matches(sword, namedEmptyNbtSword), "Empty custom NBT caused a name restriction to be discarded");
        CompoundTag nestedEmptyNbt = new CompoundTag();
        nestedEmptyNbt.put("nested", new CompoundTag());
        ItemStack nestedNbtSword = sword.copy();
        nestedNbtSword.set(DataComponents.CUSTOM_DATA, CustomData.of(nestedEmptyNbt));
        require(!matches(sword, nestedNbtSword), "A nonempty outer compound was treated as empty NBT");
        require(!LegacyCraftingStackMatcher.override(true, sword, nestedNbtSword, ComponentMatchType.STRICT, null),
                "Crafting discarded nonempty outer NBT containing an empty child");
        ItemStack namedSword = sword.copy();
        namedSword.set(DataComponents.CUSTOM_NAME, Component.literal("Extra NBT"));
        require(ItemMatchingSystem.INSTANCE.doesItemMatch(sword, namedSword, ComponentMatchType.FUZZY, null),
                "Pinned FTB fuzzy no longer demonstrates the extra-component regression");
        require(!LegacyCraftingStackMatcher.override(true, sword, namedSword, ComponentMatchType.STRICT, null),
                "Crafting accepted named item for empty legacy NBT");
        require(LegacyCraftingStackMatcher.override(true, sword, namedSword, ComponentMatchType.NONE, null),
                "Crafting ignoreNBT mode failed to ignore extra components");
        ItemStack wornSword = sword.copy();
        wornSword.setDamageValue(10);
        require(LegacyCraftingStackMatcher.override(true, sword, wornSword, ComponentMatchType.STRICT, null),
                "Crafting rejected wear ignored by legacy item matching");
        require(wornSword.getDamageValue() == 10, "Crafting predicate mutated the event stack");
        require(!strict(sword).test(wornSword), "Ordinary FTB strict matching was changed");
        require(LegacyCraftingStackMatcher.override(false, sword, wornSword, ComponentMatchType.STRICT, null) == null,
                "Unmarked FTB task was overridden");
        require(LegacyCraftingStackMatcher.override(false, ItemStack.EMPTY, namedSword, ComponentMatchType.FUZZY, null) == null,
                "Unmarked FTB filter policy was checked or overridden");
        boolean unsupported = false;
        try { LegacyCraftingStackMatcher.override(true, sword, namedSword, ComponentMatchType.FUZZY, null); }
        catch (IllegalStateException expected) { unsupported = true; }
        require(unsupported, "Unreviewed fuzzy crafting configuration was accepted");
        unsupported = false;
        try { LegacyCraftingStackMatcher.override(true, ItemStack.EMPTY, namedSword, ComponentMatchType.STRICT, null); }
        catch (IllegalStateException expected) { unsupported = true; }
        require(unsupported, "Empty legacy crafting requirement became a wildcard");
        System.out.println("{\"test\":\"actual_legacy_retrieval_durability_matcher\",\"passed\":true,\"assertions\":" + checks
                + ",\"vanilla_damageable_items\":" + damageableItems
                + ",\"actual_vanilla_itemstacks\":true,\"actual_ftb_item_matcher\":true,\"crafting_empty_nbt_predicate_verified\":true,\"absent_and_empty_custom_data_equivalent\":true,\"nonempty_custom_data_preserved\":true,\"ordinary_ftb_matching_unchanged\":true,\"mixin_application_verified\":false,\"game_runtime_verified\":false}");
    }
}

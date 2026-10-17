package org.gtnhmigration.compat;

import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem.ComponentMatchType;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Matching for exported crafting requirements with no legacy NBT/ore predicate. */
public final class LegacyCraftingStackMatcher {
    private LegacyCraftingStackMatcher() {}

    /** Null means the ordinary FTB task must keep its original implementation. */
    public static Boolean override(boolean legacyCrafting, ItemStack required, ItemStack sample,
            ComponentMatchType mode, HolderLookup.Provider registry) {
        if (!legacyCrafting) return null;
        if (mode != ComponentMatchType.STRICT && mode != ComponentMatchType.NONE) {
            throw new IllegalStateException("Unsupported legacy crafting component policy: " + mode);
        }
        if (required.isEmpty() || ItemMatchingSystem.INSTANCE.isItemFilter(required)) {
            throw new IllegalStateException("Legacy crafting requires a concrete nonempty item identity");
        }
        return LegacyRetrievalStackMatcher.matches(required, sample,
                normalized -> ItemMatchingSystem.INSTANCE.doesItemMatch(required, normalized, mode, registry));
    }
}

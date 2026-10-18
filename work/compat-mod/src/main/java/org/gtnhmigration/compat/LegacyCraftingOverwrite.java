/* GT5-Unofficial 5.09.54.205 GTShapelessRecipe overwrite phase, adapted under
 * LGPL-3.0. See NOTICE, LGPL-3.0.txt and GPL-3.0.txt. */
package org.gtnhmigration.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/** First tagged input with the same source item/damage; output top-level keys win. */
public final class LegacyCraftingOverwrite {
    private LegacyCraftingOverwrite() {}
    public static boolean identitiesAvailable(CraftingInput input, ItemStack result) {
        if (result.isEmpty()) return true;
        if (!result.has(LegacyItemIdentity.TYPE)) return false;
        for (ItemStack stack : input.items()) if (!stack.isEmpty() && !stack.has(LegacyItemIdentity.TYPE)) return false;
        return true;
    }
    public static ItemStack apply(CraftingInput input, ItemStack template) {
        ItemStack result = template.copy();
        if (result.isEmpty()) return result;
        if (!identitiesAvailable(input, result)) throw new IllegalStateException("Legacy overwrite requires explicit source identities");
        LegacyItemIdentity outputIdentity = result.get(LegacyItemIdentity.TYPE);
        for (ItemStack stack : input.items()) {
            if (stack.isEmpty() || !outputIdentity.sameItemAndDamage(stack.get(LegacyItemIdentity.TYPE))) continue;
            LegacyItemTag tag = stack.get(LegacyItemTag.TYPE);
            if (tag != null) {
                LegacyItemTag overrides = result.get(LegacyItemTag.TYPE);
                result.set(LegacyItemTag.TYPE, overrides == null ? tag : tag.withTopLevelOverrides(overrides));
                break;
            }
        }
        return result;
    }
}

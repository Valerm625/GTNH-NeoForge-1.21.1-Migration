/* Keeping-NBT phases ported from GT5-Unofficial 5.09.54.205 GTShapedRecipe.
 * LGPL-3.0; see NOTICE and LGPL-3.0.txt in mod resources. */
package org.gtnhmigration.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/** Operates on explicit legacy tags only. Modern component mapping and legacy
 * item update hooks are separate requirements, not inferred from CUSTOM_DATA.
 */
public final class LegacyCraftingNbt {
    private LegacyCraftingNbt() {}
    public static boolean matches(CraftingInput input) {
        boolean seen = false;
        LegacyItemTag previous = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            LegacyItemTag current = stack.get(LegacyItemTag.TYPE);
            if (seen && ((previous == null) != (current == null) || previous != null && !previous.sameTag(current))) return false;
            previous = current;
            seen = true;
        }
        return true;
    }
    public static ItemStack keep(CraftingInput input, ItemStack assembled) {
        ItemStack result = assembled.copy();
        if (result.isEmpty()) return result;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            LegacyItemTag tag = stack.get(LegacyItemTag.TYPE);
            if (!stack.isEmpty() && tag != null) {
                result.set(LegacyItemTag.TYPE, tag); // immutable, with defensive raw access
                break;
            }
        }
        return result;
    }
}

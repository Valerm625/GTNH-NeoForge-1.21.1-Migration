/* Matching ported from Forge 10.13.4.1614 ShapelessOreRecipe.
 * Minecraft Forge Public Licence 1.0; see NOTICE and MinecraftForge-License.txt. */
package org.gtnhmigration.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;

/** Preserve the original ordered greedy search, including overlapping choices. */
public final class LegacyShapelessMatch {
    private LegacyShapelessMatch() {}
    public static boolean matches(CraftingInput grid, List<Ingredient> ingredients) {
        List<Ingredient> required = new ArrayList<>(ingredients);
        for (int slot = 0; slot < grid.size(); slot++) {
            ItemStack stack = grid.getItem(slot);
            if (stack.isEmpty()) continue;
            boolean found = false;
            for (int i = 0; i < required.size(); i++) {
                if (required.get(i).test(stack)) {
                    required.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return required.isEmpty();
    }
}

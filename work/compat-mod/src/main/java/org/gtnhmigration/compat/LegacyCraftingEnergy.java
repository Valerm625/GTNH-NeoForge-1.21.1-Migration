/*
 * Energy phase ported from GT5-Unofficial 5.09.54.205 GTShapedRecipe and
 * GTModHandler. LGPL-3.0; see NOTICE and LGPL-3.0.txt in mod resources.
 */
package org.gtnhmigration.compat;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IElectricItem;
import com.gregtechceu.gtceu.api.item.IGTTool;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/** The legacy energy phase, explicitly opted into by migrated recipes.
 * GT capabilities provide the modern item backend. This is not an IC2 item
 * implementation or the legacy NBT/enchantment/updateItemStack pipeline.
 */
public final class LegacyCraftingEnergy {
    private LegacyCraftingEnergy() {}

    public static ItemStack transfer(CraftingInput input, ItemStack assembled) {
        ItemStack result = assembled.copy();
        IElectricItem output = electric(result);
        if (output == null) return result;
        // The old request is Integer.MAX_VALUE, not an unconditional reset.
        // An output initially above this value can retain a residual charge.
        output.discharge(Integer.MAX_VALUE, output.getTier(), true, false, false);
        int charge = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack component = input.getItem(i);
            // Some modern capability constructors initialize components even
            // for a simulated query. Keep this side effect off the live grid.
            IElectricItem source = electric(component.copy());
            if (source == null) continue;
            int drained = (int) Math.max(0.0, (double) source.discharge(
                    Integer.MAX_VALUE, source.getTier(), true, false, true));
            // Preserve Java int accumulation, including the source overflow
            // behavior. A long sum or saturating addition changes the result.
            if (drained > 0 && !(component.getItem() instanceof IGTTool)) charge += drained;
        }
        if (charge > 0) output.charge(charge, output.getTier(), true, false);
        return result;
    }

    private static IElectricItem electric(ItemStack stack) {
        if (stack.isEmpty()) return null;
        IElectricItem value = GTCapabilityHelper.getElectricItem(stack);
        // The legacy isElectricItem guard excludes the sentinel MAX_VALUE tier.
        return value != null && value.getTier() < Integer.MAX_VALUE ? value : null;
    }
}

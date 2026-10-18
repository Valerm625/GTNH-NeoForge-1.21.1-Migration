package org.gtnhmigration.compat.mixin;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import org.gtnhmigration.compat.LegacyCraftingGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CraftingInput.class)
public abstract class LegacyCraftingGridMixin implements LegacyCraftingGrid {
    @Unique private int gtnhMigration$width = -1;
    @Unique private int gtnhMigration$height = -1;
    @Unique private int gtnhMigration$left;
    @Unique private int gtnhMigration$top;

    @Inject(method="ofPositioned", at=@At("RETURN"), require=4, allow=4)
    private static void gtnhMigration$keepOrigin(int width, int height, List<ItemStack> items,
            CallbackInfoReturnable<CraftingInput.Positioned> callback) {
        CraftingInput.Positioned positioned = callback.getReturnValue();
        // EMPTY is shared. Empty recipes are rejected, so never attach mutable
        // grid state to this global singleton.
        if (!positioned.input().isEmpty()) {
            ((LegacyCraftingGrid) positioned.input()).gtnhMigration$bindGrid(width, height, positioned.left(), positioned.top());
        }
    }

    @Override public void gtnhMigration$bindGrid(int width, int height, int left, int top) {
        if (gtnhMigration$width != -1) throw new IllegalStateException("Crafting grid origin already assigned");
        gtnhMigration$width = width;
        gtnhMigration$height = height;
        gtnhMigration$left = left;
        gtnhMigration$top = top;
    }
    @Override public int gtnhMigration$gridWidth() { return gtnhMigration$width; }
    @Override public int gtnhMigration$gridHeight() { return gtnhMigration$height; }
    @Override public int gtnhMigration$left() { return gtnhMigration$left; }
    @Override public int gtnhMigration$top() { return gtnhMigration$top; }

    @Inject(method="equals", at=@At("RETURN"), cancellable=true, require=3, allow=3)
    private void gtnhMigration$compareOrigins(Object other, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) {
            callback.setReturnValue(other instanceof LegacyCraftingGrid grid
                    && gtnhMigration$width == grid.gtnhMigration$gridWidth()
                    && gtnhMigration$height == grid.gtnhMigration$gridHeight()
                    && gtnhMigration$left == grid.gtnhMigration$left()
                    && gtnhMigration$top == grid.gtnhMigration$top());
        }
    }

    @Inject(method="hashCode", at=@At("RETURN"), cancellable=true, require=1, allow=1)
    private void gtnhMigration$hashOrigin(CallbackInfoReturnable<Integer> callback) {
        if (gtnhMigration$width >= 0) {
            int hash = callback.getReturnValueI();
            hash = 31 * hash + gtnhMigration$width;
            hash = 31 * hash + gtnhMigration$height;
            hash = 31 * hash + gtnhMigration$left;
            callback.setReturnValue(31 * hash + gtnhMigration$top);
        }
    }
}

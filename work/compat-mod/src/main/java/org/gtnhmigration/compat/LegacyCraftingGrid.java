package org.gtnhmigration.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/** Retains the origin lost by vanilla's cropped CraftingInput representation. */
public interface LegacyCraftingGrid {
    void gtnhMigration$bindGrid(int width, int height, int left, int top);
    int gtnhMigration$gridWidth();
    int gtnhMigration$gridHeight();
    int gtnhMigration$left();
    int gtnhMigration$top();

    static ItemStack at(CraftingInput input, int x, int y) {
        if (!(input instanceof LegacyCraftingGrid grid) || grid.gtnhMigration$gridWidth() < 0) {
            throw new IllegalStateException("Missing original crafting grid coordinates");
        }
        if (x < 0 || y < 0 || x >= grid.gtnhMigration$gridWidth() || y >= grid.gtnhMigration$gridHeight()) {
            return ItemStack.EMPTY;
        }
        int localX = x - grid.gtnhMigration$left();
        int localY = y - grid.gtnhMigration$top();
        if (localX < 0 || localY < 0 || localX >= input.width() || localY >= input.height()) {
            return ItemStack.EMPTY;
        }
        return input.getItem(localX, localY);
    }
}

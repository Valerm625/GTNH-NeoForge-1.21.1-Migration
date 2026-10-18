package org.gtnhmigration.compat.mixin;

import java.lang.ref.WeakReference;
import java.util.Optional;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import org.gtnhmigration.compat.LegacyCraftingGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla caches both hits and misses using cropped input only. A changed
 * origin must invalidate either kind before vanilla can reuse it. The existing
 * validateRecipeManager method clears entries after the weak reference resets.
 */
@Mixin(RecipeCache.class)
public abstract class LegacyCraftingCacheMixin {
    @Shadow private WeakReference<RecipeManager> cachedRecipeManager;
    @Unique private int gtnhMigration$width = -1;
    @Unique private int gtnhMigration$height = -1;
    @Unique private int gtnhMigration$left = -1;
    @Unique private int gtnhMigration$top = -1;

    @Inject(method="get", at=@At("HEAD"), require=1, allow=1)
    private void gtnhMigration$invalidateMovedGrid(Level level, CraftingInput input,
            CallbackInfoReturnable<Optional<RecipeHolder<CraftingRecipe>>> callback) {
        if (input.isEmpty()) return;
        if (!(input instanceof LegacyCraftingGrid grid) || grid.gtnhMigration$gridWidth() < 0) {
            throw new IllegalStateException("Missing original crafting grid coordinates");
        }
        if (gtnhMigration$width != grid.gtnhMigration$gridWidth() || gtnhMigration$height != grid.gtnhMigration$gridHeight()
                || gtnhMigration$left != grid.gtnhMigration$left() || gtnhMigration$top != grid.gtnhMigration$top()) {
            cachedRecipeManager = new WeakReference<>(null);
            gtnhMigration$width = grid.gtnhMigration$gridWidth();
            gtnhMigration$height = grid.gtnhMigration$gridHeight();
            gtnhMigration$left = grid.gtnhMigration$left();
            gtnhMigration$top = grid.gtnhMigration$top();
        }
    }
}

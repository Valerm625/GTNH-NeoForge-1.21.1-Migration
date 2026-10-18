package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem.ComponentMatchType;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Opt-in no-NBT crafting match correction; does not change event accounting. */
@Mixin(value=ItemTask.class, remap=false)
public abstract class LegacyCraftingItemTaskMixin {
    @Shadow private ComponentMatchType matchComponents;
    @Shadow public abstract ItemStack getItemStack();
    @Shadow public abstract boolean isOnlyFromCrafting();
    @Shadow public abstract boolean consumesResources();

    @Inject(method="test", at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private void gtnhMigration$legacyCraftingMatch(ItemStack sample, CallbackInfoReturnable<Boolean> ci) {
        Task task=(Task)(Object)this;
        if (!task.hasTag("gtnh_legacy_crafting")) return;
        if (!isOnlyFromCrafting() || consumesResources()) {
            throw new IllegalStateException("Unsupported legacy crafting marker configuration: " + task.id);
        }
        ci.setReturnValue(LegacyCraftingStackMatcher.override(true, getItemStack(), sample,
                matchComponents, task.getQuestFile().holderLookup()));
    }
}

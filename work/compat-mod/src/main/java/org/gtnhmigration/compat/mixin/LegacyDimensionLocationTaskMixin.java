package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.LocationTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Opt-in adapter for reviewed BetterQuesting dimension-only location tasks.
 * It intentionally does not alter ordinary FTB LocationTask coordinate boxes.
 */
@Mixin(value=LocationTask.class, remap=false)
public abstract class LegacyDimensionLocationTaskMixin {
    @Shadow private ResourceKey<Level> dimension;
    @Inject(method="canSubmit", at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private void gtnhMigration$dimensionOnly(TeamData data, ServerPlayer player, CallbackInfoReturnable<Boolean> ci) {
        Task task=(Task)(Object)this;
        if (!task.hasTag("gtnh_legacy_dimension_only")) return;
        if (dimension == null) throw new IllegalStateException("Missing dimension on legacy location task: " + task.id);
        ci.setReturnValue(dimension == player.level().dimension());
    }
}

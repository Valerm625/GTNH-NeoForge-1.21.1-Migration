package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;
import dev.ftb.mods.ftbquests.quest.task.Task;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Initial inventory listener synchronization already queues the legacy scan;
 * do not also run marked tasks synchronously inside the FTB login pass.
 */
@Mixin(value=Task.class,remap=false)
public abstract class LegacyTaskLoginTimingMixin {
    @Inject(method="checkOnLogin",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void gtnhMigration$deferLegacyLogin(CallbackInfoReturnable<Boolean> ci) {
        if(LegacyInventoryObserver.isMarked((Task)(Object)this)) ci.setReturnValue(false);
    }
}

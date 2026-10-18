package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.denyLifecycle;

@Mixin(value=ServerPlayer.class,remap=false)
public abstract class LegacyPlayerLifecycleSaveScopeMixin implements LegacyRetrievalNativeSaveScope.PlayerLifecycleHooks {
    @Inject(method="tick",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerTick(CallbackInfo ci){denyLifecycle(((ServerPlayer)(Object)this).getServer(),null,"player tick");}
    @Inject(method="doTick",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerDoTick(CallbackInfo ci){denyLifecycle(((ServerPlayer)(Object)this).getServer(),null,"player simulation tick");}
    @Inject(method="die",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerDeath(CallbackInfo ci){denyLifecycle(((ServerPlayer)(Object)this).getServer(),null,"player death");}
    @Inject(method="restoreFrom",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerClone(CallbackInfo ci){denyLifecycle(((ServerPlayer)(Object)this).getServer(),null,"player clone");}
    @Inject(method="changeDimension",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerDimension(CallbackInfoReturnable<?> ci){denyLifecycle(((ServerPlayer)(Object)this).getServer(),null,"player dimension transition");}
}

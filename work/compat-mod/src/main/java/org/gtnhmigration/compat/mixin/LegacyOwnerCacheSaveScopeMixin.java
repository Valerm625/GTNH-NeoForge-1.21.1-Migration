package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;

/** Client-only configuration, without a hard reference to the client class. */
@Mixin(targets="net.minecraft.client.server.IntegratedPlayerList",remap=false)
public abstract class LegacyOwnerCacheSaveScopeMixin implements LegacyRetrievalNativeSaveScope.OwnerCacheHooks {
    @Inject(method="save",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterOwnerCache(ServerPlayer player,CallbackInfo ci){enter(this,null,Operation.PLAYER,player,Entry.OWNER_CACHE);}
}

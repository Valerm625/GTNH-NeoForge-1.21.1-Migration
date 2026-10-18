package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;

@Mixin(value=Entity.class,remap=false)
public abstract class LegacyPlayerDataLoadScopeMixin implements LegacyRetrievalNativeSaveScope.PlayerDataLoadHooks {
    @Inject(method="load",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterPlayerDataLoad(CompoundTag data,CallbackInfo ci){
        if((Object)this instanceof ServerPlayer player){
            enter(player.server,null,Operation.PLAYER_NBT_LOAD,player,Entry.ENTER);
            LegacyQuestNativeLifecycle.beforePlayerDataLoad(player,data);
        }
    }
}

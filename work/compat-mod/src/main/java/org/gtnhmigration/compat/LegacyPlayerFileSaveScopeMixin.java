package org.gtnhmigration.compat;

import java.io.File;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;

@Mixin(value=PlayerDataStorage.class,remap=false)
public abstract class LegacyPlayerFileSaveScopeMixin implements LegacyRetrievalNativeSaveScope.PlayerFileHooks {
    @Shadow public abstract File getPlayerDir();
    @Inject(method="save",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterPlayerFile(Player player,CallbackInfo ci){enter(this,getPlayerDir().toPath(),Operation.PLAYER_FILE,player,Entry.ENTER);}
    @Inject(method="load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerFileLoad(CallbackInfoReturnable<?> ci){denyLifecycle(this,getPlayerDir().toPath(),"player data load/fallback");}
}

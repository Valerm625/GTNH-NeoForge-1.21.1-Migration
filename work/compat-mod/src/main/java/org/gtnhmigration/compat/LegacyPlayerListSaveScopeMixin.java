package org.gtnhmigration.compat;

import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

@Mixin(value=PlayerList.class,remap=false)
public abstract class LegacyPlayerListSaveScopeMixin implements LegacyRetrievalNativeSaveScope.PlayerListHooks {
    @Shadow protected abstract void save(ServerPlayer player);
    @Inject(method="saveAll",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterSavePlayers(CallbackInfo ci){enter(this,null,PLAYERS,this,Entry.ENTER);}
    @Inject(method="save",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterSavePlayer(ServerPlayer player,CallbackInfo ci){enter(this,null,PLAYER,player,Entry.PLAYER_BASE);}
    @Redirect(method="saveAll",at=@At(value="INVOKE",target="Lnet/minecraft/server/players/PlayerList;save(Lnet/minecraft/server/level/ServerPlayer;)V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitOnePlayer(PlayerList receiver,ServerPlayer player){child(this,PLAYERS,PLAYER,player,()->{save(player);return null;});}
    @Redirect(method="save",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/storage/PlayerDataStorage;save(Lnet/minecraft/world/entity/player/Player;)V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitPlayerFile(PlayerDataStorage storage,Player player){child(this,PLAYER,PLAYER_FILE,player,()->{storage.save(player);return null;});}
    @Inject(method="load",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerLoad(CallbackInfoReturnable<?> ci){denyLifecycle(this,null,"player load");}
    @Inject(method="placeNewPlayer",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerJoin(CallbackInfo ci){denyLifecycle(this,null,"player admission");}
    @Inject(method="remove",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerRemove(CallbackInfo ci){denyLifecycle(this,null,"player logout/removal");}
    @Inject(method="respawn",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerRespawn(CallbackInfoReturnable<?> ci){denyLifecycle(this,null,"player respawn");}
}

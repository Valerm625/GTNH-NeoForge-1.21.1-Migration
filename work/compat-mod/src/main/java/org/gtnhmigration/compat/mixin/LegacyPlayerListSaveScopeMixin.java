package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
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
    private void gtnhMigration$denyPlayerLoad(ServerPlayer player,CallbackInfoReturnable<?> ci){enter(this,null,PLAYER_LOAD,player,Entry.ENTER);LegacyQuestNativeLifecycle.requireListLoad((PlayerList)(Object)this,player);}
    @Inject(method="placeNewPlayer",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerJoin(CallbackInfo ci){denyLifecycle(this,null,"player admission");}
    @Inject(method="remove",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerRemove(CallbackInfo ci){denyLifecycle(this,null,"player logout/removal");}
    @Inject(method="respawn",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyPlayerRespawn(CallbackInfoReturnable<?> ci){denyLifecycle(this,null,"player respawn");}
    @Redirect(method="placeNewPlayer",at=@At(value="INVOKE",target="Lnet/minecraft/server/players/PlayerList;load(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Optional;"),require=1,allow=1,remap=false)
    private Optional<CompoundTag> gtnhMigration$guardAdmission(PlayerList list,ServerPlayer player){return LegacyQuestNativeLifecycle.load(list,player);}
    @Redirect(method="load",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/storage/PlayerDataStorage;load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;"),require=1,allow=1,remap=false)
    private Optional<CompoundTag> gtnhMigration$permitPlayerLoadFile(PlayerDataStorage storage,Player player){return LegacyQuestNativeLifecycle.loadFile(storage,player);}
    @Redirect(method="load",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;load(Lnet/minecraft/nbt/CompoundTag;)V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitOwnerLoadData(ServerPlayer player,CompoundTag data){LegacyQuestNativeLifecycle.loadData(player,data,false);}

}

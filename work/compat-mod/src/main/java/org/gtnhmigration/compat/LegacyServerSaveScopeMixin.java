package org.gtnhmigration.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

@Mixin(value=MinecraftServer.class,remap=false)
public abstract class LegacyServerSaveScopeMixin implements LegacyRetrievalNativeSaveScope.ServerAccess {
    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow @Final protected PlayerDataStorage playerDataStorage;
    public LevelStorageSource.LevelStorageAccess gtnhMigration$worldStorage(){return storageSource;}
    public PlayerDataStorage gtnhMigration$playerStorage(){return playerDataStorage;}
    @Inject(method="saveEverything",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterSaveEverything(boolean a,boolean b,boolean c,CallbackInfoReturnable<Boolean> ci){enter(this,null,EVERYTHING,this,Entry.ENTER);}
    @Inject(method="saveAllChunks",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterSaveChunks(boolean a,boolean b,boolean c,CallbackInfoReturnable<Boolean> ci){enter(this,null,CHUNKS,this,Entry.ENTER);}
    @Inject(method="tickServer",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyNestedTick(CallbackInfo ci){denyLifecycle(this,null,"server tick");}
    @Inject(method="tickChildren",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyChildTicks(CallbackInfo ci){denyLifecycle(this,null,"world/player ticks after server Pre callbacks");}
    @Inject(method="stopServer",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyNestedStop(CallbackInfo ci){denyLifecycle(this,null,"server stop");}
    @Redirect(method="saveEverything",at=@At(value="INVOKE",target="Lnet/minecraft/server/players/PlayerList;saveAll()V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitPlayerListSave(PlayerList list){child(this,EVERYTHING,PLAYERS,list,()->{list.saveAll();return null;});}
    @Redirect(method="saveEverything",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"),require=1,allow=1,remap=false)
    private boolean gtnhMigration$permitChunkSave(MinecraftServer server,boolean a,boolean b,boolean c){return child(this,EVERYTHING,CHUNKS,server,()->server.saveAllChunks(a,b,c));}
    @Redirect(method="saveAllChunks",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitWorldTag(LevelStorageSource.LevelStorageAccess storage,RegistryAccess registries,WorldData data,CompoundTag player){child(this,CHUNKS,LEVEL_TAG,storage,()->{storage.saveDataTag(registries,data,player);return null;});}
}

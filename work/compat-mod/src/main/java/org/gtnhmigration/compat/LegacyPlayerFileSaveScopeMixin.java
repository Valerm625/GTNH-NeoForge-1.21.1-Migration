package org.gtnhmigration.compat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.*;
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
    private void gtnhMigration$denyPlayerFileLoad(Player player,CallbackInfoReturnable<?> ci){enter(this,getPlayerDir().toPath(),Operation.PLAYER_FILE_LOAD,player,Entry.ENTER);LegacyQuestNativeLifecycle.requireFileLoad(player);}
    @Redirect(method="load(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;)Ljava/util/Optional;",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/NbtIo;readCompressed(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;"),require=1,allow=1,remap=false)
    private CompoundTag gtnhMigration$verifiedPlayerRead(Path path,NbtAccounter accounter)throws IOException{return LegacyQuestNativeLifecycle.readPlayerFile(path,accounter);}
    @Inject(method="backup",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$beforePlayerBackup(Player player,String suffix,CallbackInfo ci){LegacyQuestNativeLifecycle.beforeBackup(player,suffix);}
    @Redirect(method="lambda$load$1",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;load(Lnet/minecraft/nbt/CompoundTag;)V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitFileLoadData(Player player,CompoundTag data){LegacyQuestNativeLifecycle.loadData(player,data,true);}

}

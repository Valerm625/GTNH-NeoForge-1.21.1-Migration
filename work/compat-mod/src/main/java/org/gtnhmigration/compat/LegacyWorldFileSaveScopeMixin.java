package org.gtnhmigration.compat;

import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

@Mixin(value=LevelStorageSource.LevelStorageAccess.class,remap=false)
public abstract class LegacyWorldFileSaveScopeMixin implements LegacyRetrievalNativeSaveScope.WorldFileHooks {
    @Shadow public abstract Path getWorldDir();
    @Shadow private void saveLevelData(CompoundTag data){throw new AssertionError("Mixin shadow");}
    @Inject(method="saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterWorldTag(CallbackInfo ci){enter(this,getWorldDir(),LEVEL_TAG,this,Entry.ENTER);}
    @Inject(method="saveLevelData",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$enterWorldFile(CallbackInfo ci){enter(this,getWorldDir(),LEVEL_FILE,this,Entry.ENTER);}
    @Redirect(method="saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V"),require=1,allow=1,remap=false)
    private void gtnhMigration$permitWorldFile(LevelStorageSource.LevelStorageAccess receiver,CompoundTag data){child(this,LEVEL_TAG,LEVEL_FILE,this,()->{saveLevelData(data);return null;});}
    @Inject(method="close",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyWorldClose(CallbackInfo ci){denyLifecycle(this,getWorldDir(),"world storage close");}
    @Inject(method="deleteLevel",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyWorldDelete(CallbackInfo ci){denyLifecycle(this,getWorldDir(),"world deletion");}
    @Inject(method="renameLevel",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void gtnhMigration$denyWorldRename(CallbackInfo ci){denyLifecycle(this,getWorldDir(),"world metadata rename");}
}

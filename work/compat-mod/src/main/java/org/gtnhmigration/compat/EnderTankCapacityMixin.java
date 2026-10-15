package org.gtnhmigration.compat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
/** Source: supplied master config/EnderStorage.cfg, enderTankSize=256.
 * Pinned against EnderStorage 2.13.0.191. Leave third-party JAR intact.
 * Changes the shared CAPACITY constant for server storage, cleared storage,
 * and client rendering/sync users consistently; do not patch getCapacity alone.
 */
@Mixin(targets="codechicken.enderstorage.storage.EnderLiquidStorage", remap=false)
public abstract class EnderTankCapacityMixin {
    @ModifyConstant(method="<clinit>", constant=@Constant(intValue=16), require=1, allow=1, remap=false)
    private static int gtnhMigration$restoreBucketCount(int original) {
        if (original != 16) throw new IllegalStateException("Unexpected EnderStorage capacity; re-audit target version");
        return 256;
    }
}

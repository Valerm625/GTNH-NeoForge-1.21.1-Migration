package org.gtnhmigration.compat;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BQ always sent an initial full-inventory notification. Modern menu listener
 * registration only broadcasts changed slots, so an empty or already-synced
 * inventory needs an explicit initial request. The invocation point excludes
 * duplicate registration of an existing listener.
 */
@Mixin(value=AbstractContainerMenu.class,remap=false)
public abstract class LegacyInitialInventorySyncMixin {
    @Inject(method="addSlotListener",at=@At(value="INVOKE",target="Lnet/minecraft/world/inventory/AbstractContainerMenu;broadcastChanges()V"),require=1,allow=1,remap=false)
    private void gtnhMigration$initialLegacyInventory(ContainerListener listener,CallbackInfo ci) {
        if(listener instanceof FTBQuestsInventoryListener ftb && (Object)this==ftb.player.inventoryMenu) {
            LegacyInventoryObserver.schedule(ftb.player);
        }
    }
}

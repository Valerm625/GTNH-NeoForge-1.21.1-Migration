package org.gtnhmigration.compat;
import net.neoforged.fml.common.Mod;
/** Explicit GTNH migration adaptations, not a claim of complete port. */
@Mod("gtnh_migration_compat")
public final class MigrationCompat { public MigrationCompat(net.neoforged.bus.api.IEventBus bus) { LegacyShapedRecipe.register(bus); LegacyShapelessRecipe.register(bus); LegacyItemTag.register(bus); } }

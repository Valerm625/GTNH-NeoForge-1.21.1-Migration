package org.gtnhmigration.compat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Explicit source registry identity and raw old damage, not modern item equality.
 * Importers and item behavior adapters must maintain it; no implicit mapping exists.
 */
public record LegacyItemIdentity(String item, int damage) {
    public LegacyItemIdentity {
        if (item == null || item.isEmpty()) throw new IllegalArgumentException("Empty source item identity");
    }
    public static final Codec<LegacyItemIdentity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.validate(value -> value.isEmpty() ? DataResult.error(() -> "Empty source item identity") : DataResult.success(value)).fieldOf("item").forGetter(LegacyItemIdentity::item),
            Codec.INT.fieldOf("damage").forGetter(LegacyItemIdentity::damage)
    ).apply(instance, LegacyItemIdentity::new));
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "gtnh_migration");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LegacyItemIdentity>> TYPE = COMPONENTS.registerComponentType("legacy_item_identity",
            builder -> builder.persistent(CODEC).networkSynchronized(ByteBufCodecs.fromCodec(CODEC)));
    public static void register(IEventBus bus) { COMPONENTS.register(bus); }
    public boolean sameItemAndDamage(LegacyItemIdentity other) {
        return other != null && item.equals(other.item) && (damage == other.damage || damage == 32767 || other.damage == 32767);
    }
}

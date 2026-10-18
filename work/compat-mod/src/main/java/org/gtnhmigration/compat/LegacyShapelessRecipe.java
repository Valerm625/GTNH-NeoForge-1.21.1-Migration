package org.gtnhmigration.compat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.latvian.mods.kubejs.recipe.ingredientaction.IngredientActionHolder;
import dev.latvian.mods.kubejs.recipe.special.ShapelessKubeJSRecipe;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Legacy matching and explicitly selected keep/energy phases. Full item NBT
 * mapping, validators, enchantments and item updates remain separate.
 */
public final class LegacyShapelessRecipe extends ShapelessKubeJSRecipe {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "gtnh_migration");
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<LegacyShapelessRecipe>> SERIALIZER = SERIALIZERS.register("legacy_shapeless", Serializer::new);
    private final ItemStack output;
    private final boolean keepLegacyNbt, legacyEnergy, overwriteLegacyNbt;
    public static void register(IEventBus bus) { SERIALIZERS.register(bus); }

    public LegacyShapelessRecipe(String group, CraftingBookCategory category, List<Ingredient> ingredients,
            ItemStack output, boolean keepLegacyNbt, boolean legacyEnergy, boolean overwriteLegacyNbt,
            List<IngredientActionHolder> actions, String modifyResult) {
        super(new ShapelessRecipe(group, category, output, copy(ingredients)), actions, modifyResult);
        this.output = output;
        this.keepLegacyNbt = keepLegacyNbt;
        this.legacyEnergy = legacyEnergy;
        this.overwriteLegacyNbt = overwriteLegacyNbt;
    }
    private static NonNullList<Ingredient> copy(List<Ingredient> ingredients) {
        NonNullList<Ingredient> result = NonNullList.create(); result.addAll(ingredients); return result;
    }
    @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
    @Override public boolean matches(CraftingInput input, Level level) {
        return (!overwriteLegacyNbt || LegacyCraftingOverwrite.identitiesAvailable(input, output))
                && (!keepLegacyNbt || LegacyCraftingNbt.matches(input)) && LegacyShapelessMatch.matches(input, getIngredients());
    }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = super.assemble(input, registries);
        if (keepLegacyNbt) result = LegacyCraftingNbt.keep(input, result);
        if (overwriteLegacyNbt) result = LegacyCraftingOverwrite.apply(input, result);
        return legacyEnergy ? LegacyCraftingEnergy.transfer(input, result) : result;
    }
    public static final class Serializer implements RecipeSerializer<LegacyShapelessRecipe> {
        public static final MapCodec<LegacyShapelessRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("group", "").forGetter(LegacyShapelessRecipe::getGroup),
                CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(LegacyShapelessRecipe::category),
                Ingredient.CODEC.listOf().fieldOf("ingredients").forGetter(LegacyShapelessRecipe::getIngredients),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.output),
                Codec.BOOL.optionalFieldOf("gtnh:keep_legacy_nbt", false).forGetter(recipe -> recipe.keepLegacyNbt),
                Codec.BOOL.optionalFieldOf("gtnh:legacy_energy", false).forGetter(recipe -> recipe.legacyEnergy),
                Codec.BOOL.optionalFieldOf("gtnh:overwrite_legacy_nbt", false).forGetter(recipe -> recipe.overwriteLegacyNbt),
                IngredientActionHolder.LIST_CODEC.optionalFieldOf(INGREDIENT_ACTIONS_KEY, List.of()).forGetter(LegacyShapelessRecipe::kjs$getIngredientActions),
                Codec.STRING.optionalFieldOf(MODIFY_RESULT_KEY, "").forGetter(LegacyShapelessRecipe::kjs$getModifyResult)
        ).apply(instance, LegacyShapelessRecipe::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyShapelessRecipe> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());
        @Override public MapCodec<LegacyShapelessRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, LegacyShapelessRecipe> streamCodec() { return STREAM_CODEC; }
    }
}

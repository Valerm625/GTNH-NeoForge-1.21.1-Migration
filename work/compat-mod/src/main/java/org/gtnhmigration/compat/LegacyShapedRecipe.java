/*
 * Shaped-grid matching ported from Minecraft Forge 10.13.4.1614
 * net.minecraftforge.oredict.ShapedOreRecipe, with modern ingredient/grid adapters.
 * This file is under the Minecraft Forge Public Licence, Version 1.0.
 * See MinecraftForge-License.txt and NOTICE in the mod resources; fixed source
 * and archive provenance are recorded in locks/legacy-shaped-source.json.
 */
package org.gtnhmigration.compat;

import java.util.List;
import java.util.Optional;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.latvian.mods.kubejs.recipe.ingredientaction.IngredientActionHolder;
import dev.latvian.mods.kubejs.recipe.special.ShapedKubeJSRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Legacy Forge shaped-grid geometry plus the existing KubeJS crafting actions.
 * An explicit legacy-energy option runs the old energy phase. This does not
 * implement GTNH's NBT/enchantment/item-update pipeline or research gates.
 */
public final class LegacyShapedRecipe extends ShapedKubeJSRecipe {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "gtnh_migration");
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<LegacyShapedRecipe>> SERIALIZER = SERIALIZERS.register("legacy_shaped", Serializer::new);
    private final ShapedRecipePattern.Data source;
    private final ItemStack output;
    private final boolean shrink;
    private final boolean legacyEnergy;

    public static void register(IEventBus bus) { SERIALIZERS.register(bus); }

    public LegacyShapedRecipe(String group, CraftingBookCategory category, ShapedRecipePattern.Data source,
            ItemStack output, boolean showNotification, boolean mirror, boolean shrink, boolean legacyEnergy,
            List<IngredientActionHolder> actions, String modifyResult) {
        super(group, category, pattern(source, shrink), output, showNotification, mirror, actions, modifyResult);
        this.source = source;
        this.output = output;
        this.shrink = shrink;
        this.legacyEnergy = legacyEnergy;
    }

    private static ShapedRecipePattern pattern(ShapedRecipePattern.Data data, boolean shrink) {
        // Retain vanilla's key validation (unknown/unused symbols and empty patterns).
        ShapedRecipePattern trimmed = ShapedRecipePattern.of(data.key(), data.pattern());
        if (shrink) return trimmed;
        int width = data.pattern().getFirst().length(), height = data.pattern().size();
        NonNullList<Ingredient> ingredients = NonNullList.withSize(width * height, Ingredient.EMPTY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                char symbol = data.pattern().get(y).charAt(x);
                if (symbol != ' ') ingredients.set(x + y * width, data.key().get(symbol));
            }
        }
        return new ShapedRecipePattern(width, height, ingredients, Optional.of(data));
    }

    @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }

    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack assembled = super.assemble(input, registries);
        return legacyEnergy ? LegacyCraftingEnergy.transfer(input, assembled) : assembled;
    }

    @Override public boolean matches(CraftingInput input, Level level) {
        if (shrink) return super.matches(input, level);
        if (input.isEmpty()) return false;
        // Forge 10.13.4.1614 uses a logical 3x3 grid even for the 2x2 player
        // inventory. Out-of-inventory cells are empty. Do not substitute the
        // cropped input size or silently move padded patterns to the origin.
        int width = source.pattern().getFirst().length(), height = source.pattern().size();
        for (int startX = 0; startX <= 3 - width; startX++) {
            for (int startY = 0; startY <= 3 - height; startY++) {
                if (matchesAt(input, startX, startY, false)) return true;
                if (kjs$getMirror() && matchesAt(input, startX, startY, true)) return true;
            }
        }
        return false;
    }

    private boolean matchesAt(CraftingInput input, int startX, int startY, boolean mirror) {
        int width = source.pattern().getFirst().length(), height = source.pattern().size();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                int localX = x - startX, localY = y - startY;
                Ingredient expected = Ingredient.EMPTY;
                if (localX >= 0 && localY >= 0 && localX < width && localY < height) {
                    char symbol = source.pattern().get(localY).charAt(mirror ? width - localX - 1 : localX);
                    if (symbol != ' ') expected = source.key().get(symbol);
                }
                if (!expected.test(LegacyCraftingGrid.at(input, x, y))) return false;
            }
        }
        return true;
    }

    public static final class Serializer implements RecipeSerializer<LegacyShapedRecipe> {
        public static final MapCodec<LegacyShapedRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("group", "").forGetter(LegacyShapedRecipe::getGroup),
                CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(LegacyShapedRecipe::category),
                ShapedRecipePattern.Data.MAP_CODEC.forGetter(recipe -> recipe.source),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.output),
                Codec.BOOL.optionalFieldOf("show_notification", true).forGetter(LegacyShapedRecipe::showNotification),
                Codec.BOOL.optionalFieldOf(MIRROR_KEY, true).forGetter(LegacyShapedRecipe::kjs$getMirror),
                Codec.BOOL.optionalFieldOf("kubejs:shrink", true).forGetter(recipe -> recipe.shrink),
                Codec.BOOL.optionalFieldOf("gtnh:legacy_energy", false).forGetter(recipe -> recipe.legacyEnergy),
                IngredientActionHolder.LIST_CODEC.optionalFieldOf(INGREDIENT_ACTIONS_KEY, List.of()).forGetter(LegacyShapedRecipe::kjs$getIngredientActions),
                Codec.STRING.optionalFieldOf(MODIFY_RESULT_KEY, "").forGetter(LegacyShapedRecipe::kjs$getModifyResult)
        ).apply(instance, LegacyShapedRecipe::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyShapedRecipe> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());
        @Override public MapCodec<LegacyShapedRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, LegacyShapedRecipe> streamCodec() { return STREAM_CODEC; }
    }
}

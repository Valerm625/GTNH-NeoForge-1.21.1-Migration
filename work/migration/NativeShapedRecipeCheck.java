package org.gtnhmigration.startupaudit;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Real recipe/matching/codec calls; no server, world, player or crafting menu. */
public final class NativeShapedRecipeCheck {
    private static CraftingRecipe recipe(RecipeManager manager, String name) {
        return (CraftingRecipe)manager.byKey(ResourceLocation.parse("gtnh_audit:shape_"+name)).orElseThrow().value();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static CraftingInput input(JsonObject grid) {
        int size=grid.get("size").getAsInt();
        List<ItemStack> items=new ArrayList<>();
        for (JsonElement value : grid.getAsJsonArray("items")) {
            items.add(switch(value.getAsString()) {
                case "A" -> new ItemStack(Items.IRON_INGOT);
                case "B" -> new ItemStack(Items.GOLD_INGOT);
                case "" -> ItemStack.EMPTY;
                default -> throw new IllegalArgumentException("Unknown oracle token");
            });
        }
        return CraftingInput.of(size,size,items);
    }
    public static JsonObject run(RecipeManager manager, RegistryOps<JsonElement> ops, net.minecraft.core.RegistryAccess registries) throws Exception {
        JsonObject oracle=JsonParser.parseString(Files.readString(Path.of("shaped-oracle.json"))).getAsJsonObject();
        int matches=0, roundTrips=0;
        for (JsonElement element : oracle.getAsJsonArray("cases")) {
            JsonObject row=element.getAsJsonObject(); String name=row.get("name").getAsString();
            CraftingRecipe original=recipe(manager,name);
            JsonElement encoded=Recipe.CODEC.encodeStart(ops,original).getOrThrow();
            check(encoded.getAsJsonObject().get("type").getAsString().equals("gtnh_migration:legacy_shaped"),"noShrink serializer missing: "+name);
            check(!encoded.getAsJsonObject().get("kubejs:shrink").getAsBoolean(),"noShrink flag missing: "+name);
            CraftingRecipe json=(CraftingRecipe)Recipe.CODEC.parse(ops,encoded).getOrThrow();
            RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
            CraftingRecipe network;
            try {
                Recipe.STREAM_CODEC.encode(buffer,original);
                network=(CraftingRecipe)Recipe.STREAM_CODEC.decode(buffer);
                check(!buffer.isReadable(),"Unread shaped recipe network bytes");
            } finally { buffer.release(); }
            for(CraftingRecipe value:List.of(original,json,network)) {
                check(encoded.equals(Recipe.CODEC.encodeStart(ops,value).getOrThrow()),"Shaped codec changed pattern/actions: "+name);
                for(JsonElement item:row.getAsJsonArray("grids")) {
                    JsonObject grid=item.getAsJsonObject();
                    boolean expected=grid.get("matches").getAsBoolean();
                    check(value.matches(input(grid),null)==expected,"Legacy shaped geometry differs: "+name+" "+grid);
                    matches++;
                }
            }
            roundTrips+=2;
        }
        check(matches==oracle.get("comparisons").getAsInt()*3,"Incomplete shaped source comparison");
        int shrinkChecks=0;
        for(String name:List.of("default","reset")) {
            CraftingRecipe recipe=recipe(manager,name);
            check(Recipe.CODEC.encodeStart(ops,recipe).getOrThrow().getAsJsonObject().get("type").getAsString().equals("kubejs:shaped"),"Default shrink behavior changed");
            for(int size:new int[]{2,3}) for(int slot=0;slot<size*size;slot++) {
                List<ItemStack> slots=new ArrayList<>(Collections.nCopies(size*size,ItemStack.EMPTY));
                slots.set(slot,new ItemStack(Items.IRON_INGOT));
                check(recipe.matches(CraftingInput.of(size,size,slots),null),"Default shrinking rejected shifted input");
                shrinkChecks++;
            }
        }
        int remainingChecks=0;
        for(String name:List.of("bucket","keep")) {
            CraftingRecipe recipe=recipe(manager,name);
            List<ItemStack> slots=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));
            slots.set(4,new ItemStack(Items.MILK_BUCKET));
            CraftingInput grid=CraftingInput.of(3,3,slots);
            JsonElement encoded=Recipe.CODEC.encodeStart(ops,recipe).getOrThrow();
            CraftingRecipe json=(CraftingRecipe)Recipe.CODEC.parse(ops,encoded).getOrThrow();
            RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
            CraftingRecipe network;
            try { Recipe.STREAM_CODEC.encode(buffer,recipe); network=(CraftingRecipe)Recipe.STREAM_CODEC.decode(buffer); }
            finally { buffer.release(); }
            for(CraftingRecipe value:List.of(recipe,json,network)) {
                check(encoded.equals(Recipe.CODEC.encodeStart(ops,value).getOrThrow()),"Ingredient action codec changed");
                check(value.matches(grid,null),"Padded crafting remainder fixture did not match");
                check(value.assemble(grid,registries).is(Items.CLAY_BALL),"Crafting result changed");
                var remaining=value.getRemainingItems(grid);
                check(remaining.size()==1&&remaining.getFirst().is(name.equals("keep")?Items.MILK_BUCKET:Items.BUCKET),"KubeJS ingredient action/remainder changed");
                check(grid.getItem(0).is(Items.MILK_BUCKET),"Matching/remainder mutated input");
                remainingChecks++;
            }
            roundTrips+=2;
        }
        // Exercise the real get() head, then intentionally stop at the required
        // Level lookup. No Level instance is fabricated; world-backed cache reuse
        // is not claimed by this boundary check.
        List<ItemStack> centerSlots=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));
        centerSlots.set(4,new ItemStack(Items.IRON_INGOT));
        CraftingInput center=CraftingInput.of(3,3,centerSlots), duplicate=CraftingInput.of(3,3,new ArrayList<>(centerSlots));
        List<ItemStack> cornerSlots=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));
        cornerSlots.set(0,new ItemStack(Items.IRON_INGOT));
        CraftingInput corner=CraftingInput.of(3,3,cornerSlots);
        CraftingInput small=CraftingInput.of(2,2,List.of(ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY,new ItemStack(Items.IRON_INGOT)));
        check(center.equals(center),"Crafting input identity is not reflexive");
        check(center.equals(duplicate)&&duplicate.equals(center)&&center.hashCode()==duplicate.hashCode(),"Equal original grids have different identity");
        check(!center.equals(corner)&&!corner.equals(center),"Crafting input equality ignored original position");
        check(!center.equals(small)&&!small.equals(center),"Crafting input equality ignored full grid size");
        Map<CraftingInput,String> identityCache=new HashMap<>();
        identityCache.put(center,"center");identityCache.put(corner,"corner");identityCache.put(small,"small");
        check(identityCache.size()==3&&identityCache.get(duplicate).equals("center"),"Crafting input hash cache conflated original grids");
        RecipeCache cache=new RecipeCache(4);
        var reference=RecipeCache.class.getDeclaredField("cachedRecipeManager"); reference.setAccessible(true);
        for(int step=0;step<3;step++) {
            List<ItemStack> slots=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));
            slots.set(step==2?0:4,new ItemStack(Items.IRON_INGOT));
            reference.set(cache,new java.lang.ref.WeakReference<>(manager));
            try { cache.get(null,CraftingInput.of(3,3,slots)); throw new IllegalStateException("Unexpected world-free cache completion"); }
            catch(NullPointerException expected) { /* vanilla validateRecipeManager requires the absent Level */ }
            Object retained=((java.lang.ref.WeakReference<?>)reference.get(cache)).get();
            check(step==1?retained==manager:retained==null,"Crafting cache retained a position-dependent entry");
        }
        reference.set(cache,new java.lang.ref.WeakReference<>(manager));
        check(cache.get(null,CraftingInput.EMPTY).isEmpty(),"Empty crafting cache lookup changed");
        check(((java.lang.ref.WeakReference<?>)reference.get(cache)).get()==manager,"Empty input invalidated unrelated cache");
        JsonObject result=new JsonObject(); result.addProperty("source_geometry_comparisons",matches);
        result.addProperty("json_network_round_trips",roundTrips); result.addProperty("default_and_reset_shrink_checks",shrinkChecks);
        result.addProperty("remaining_item_and_action_checks",remainingChecks); result.addProperty("cache_origin_entry_checks",4); result.addProperty("input_identity_checks",5); result.addProperty("no_world",true);
        return result;
    }
}

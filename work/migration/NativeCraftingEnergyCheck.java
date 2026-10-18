package org.gtnhmigration.startupaudit;

import com.google.gson.*;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.item.IGTTool;
import com.gregtechceu.gtceu.api.item.datacomponents.SimpleEnergyContent;
import com.gregtechceu.gtceu.data.item.GTDataComponents;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Loaded migration serializer, fixed GT5 expressions, and actual GT item capabilities. */
public final class NativeCraftingEnergyCheck {
    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static CraftingRecipe recipe(RecipeManager manager, String name) {
        return (CraftingRecipe)manager.byKey(ResourceLocation.parse("gtnh_audit:energy_"+name)).orElseThrow().value();
    }
    private static ItemStack stack(String id, long charge) {
        ItemStack result=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();
        check(!result.isEmpty(),"Missing actual electric test item: "+id);
        if(!id.equals("minecraft:iron_ingot")) {
            result.set(GTDataComponents.ENERGY_CONTENT,new SimpleEnergyContent(5_000_000_000L,charge,false,true));
            check(GTCapabilityHelper.getElectricItem(result)!=null,"Missing actual GT electric capability: "+id);
        }
        return result;
    }
    private static void compare(CraftingRecipe recipe, CraftingInput input, RegistryAccess registries) {
        List<ItemStack> before=new ArrayList<>();
        for(int i=0;i<input.size();i++) before.add(input.getItem(i).copy());
        ItemStack template=recipe.getResultItem(registries).copy();
        check(recipe.matches(input,null),"Energy fixture did not match real shaped input");
        ItemStack expected=GT5CraftingEnergyReference.apply(input,template);
        ItemStack result=recipe.assemble(input,registries);
        check(ItemStack.matches(expected,result),"Legacy energy transfer differs from fixed GT5 source");
        check(ItemStack.matches(template,recipe.getResultItem(registries)),"Energy assembly mutated recipe template");
        for(int i=0;i<input.size();i++) check(ItemStack.matches(before.get(i),input.getItem(i)),"Energy assembly mutated input");
        // A caller may edit its result without contaminating the next craft.
        result.setCount(0);
        check(ItemStack.matches(expected,recipe.assemble(input,registries)),"Energy assembly shared result state");
    }
    public static JsonObject run(RecipeManager manager, RegistryOps<JsonElement> ops, RegistryAccess registries) {
        CraftingRecipe base=recipe(manager,"legacy");
        JsonObject encoded=Recipe.CODEC.encodeStart(ops,base).getOrThrow().getAsJsonObject();
        check(encoded.get("gtnh:legacy_energy").getAsBoolean(),"Loaded recipe lost legacy energy option");
        String lv="gtceu:lv_lithium_battery",mv="gtceu:mv_lithium_battery",hv="gtceu:hv_lithium_battery";
        String tool="gtceu:lv_steel_drill",plain="minecraft:iron_ingot";
        check(stack(tool,0).getItem() instanceof IGTTool,"Tool exclusion test did not use a real GT tool");
        List<List<String>> layouts=List.of(List.of(lv,mv,hv),List.of(lv,tool,mv),List.of(lv,plain,mv),List.of(plain,plain,plain),List.of(tool,tool,tool));
        long[] values={0,37,1_500_000_000L,Integer.MAX_VALUE,(long)Integer.MAX_VALUE+33};
        long[][] outputs={{200_000,0},{200_000,83},{5_000_000_000L,0},{5_000_000_000L,83},{5_000_000_000L,(long)Integer.MAX_VALUE+33}};
        int comparisons=0,roundTrips=0;
        for(long[] output:outputs) {
            JsonObject config=encoded.deepCopy();
            ItemStack target=stack(lv,output[1]);
            target.set(GTDataComponents.ENERGY_CONTENT,new SimpleEnergyContent(output[0],output[1],true,true));
            config.add("result",ItemStack.STRICT_CODEC.encodeStart(ops,target).getOrThrow());
            CraftingRecipe original=(CraftingRecipe)Recipe.CODEC.parse(ops,config).getOrThrow();
            JsonElement canonical=Recipe.CODEC.encodeStart(ops,original).getOrThrow();
            CraftingRecipe json=(CraftingRecipe)Recipe.CODEC.parse(ops,canonical).getOrThrow();
            RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
            CraftingRecipe network;
            try{Recipe.STREAM_CODEC.encode(buffer,original);network=(CraftingRecipe)Recipe.STREAM_CODEC.decode(buffer);}
            finally{buffer.release();}
            for(CraftingRecipe value:List.of(original,json,network)) {
                check(canonical.equals(Recipe.CODEC.encodeStart(ops,value).getOrThrow()),"Legacy energy codec changed recipe");
                for(List<String> layout:layouts) for(long a:values) for(long b:values) for(long c:values) {
                    CraftingInput input=CraftingInput.of(3,1,List.of(stack(layout.get(0),a),stack(layout.get(1),b),stack(layout.get(2),c)));
                    compare(value,input,registries);comparisons++;
                }
            }
            roundTrips+=2;
        }
        // The source energy phase scans the whole inventory, including cells
        // outside the matching algorithm's fixed logical 3x3 area.
        List<ItemStack> wide=new ArrayList<>(Collections.nCopies(16,ItemStack.EMPTY));
        wide.set(0,stack(lv,11));wide.set(1,stack(mv,13));wide.set(2,stack(hv,17));wide.set(15,stack(lv,19));
        compare(base,CraftingInput.of(4,4,wide),registries);
        ItemStack stacked=stack(lv,29);stacked.setCount(2);
        compare(base,CraftingInput.of(3,1,List.of(stacked,stack(mv,31),stack(hv,37))),registries);
        compare(recipe(manager,"plain"),CraftingInput.of(3,1,List.of(stack(lv,41),stack(tool,43),stack(hv,47))),registries);
        CraftingRecipe disabled=recipe(manager,"disabled");
        CraftingInput disabledInput=CraftingInput.of(3,1,List.of(stack(lv,53),stack(mv,59),stack(hv,61)));
        check(disabled.matches(disabledInput,null),"Disabled energy fixture did not match");
        check(ItemStack.matches(disabled.getResultItem(registries),disabled.assemble(disabledInput,registries)),"Default recipe unexpectedly transferred energy");
        // Explicit numeric witnesses prevent source/API adapters from agreeing
        // on the same accidental no-op implementation.
        ItemStack summed=base.assemble(CraftingInput.of(3,1,List.of(stack(lv,11),stack(tool,999),stack(hv,17))),registries);
        check(summed.get(GTDataComponents.ENERGY_CONTENT).charge()==28,"All battery inputs/tool exclusion not applied");
        ItemStack overflow=base.assemble(CraftingInput.of(3,1,List.of(stack(lv,1_500_000_000L),stack(mv,1_500_000_000L),stack(plain,0))),registries);
        check(overflow.get(GTDataComponents.ENERGY_CONTENT).charge()==0,"Legacy int overflow was changed");
        JsonObject report=new JsonObject();report.addProperty("source_comparisons",comparisons);
        report.addProperty("json_network_round_trips",roundTrips);report.addProperty("boundary_checks",6);
        report.addProperty("real_gt_capabilities",true);report.addProperty("input_and_template_unchanged",true);
        report.addProperty("ic2_runtime_verified",false);report.addProperty("world_or_player_executed",false);
        return report;
    }
}

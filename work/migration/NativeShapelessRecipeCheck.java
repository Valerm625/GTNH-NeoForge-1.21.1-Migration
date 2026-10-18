package org.gtnhmigration.startupaudit;

import com.google.gson.*;
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
import org.gtnhmigration.compat.LegacyItemTag;

/** Installed serializer with fixed Forge/GT5 source, independent fixture choices. */
public final class NativeShapelessRecipeCheck {
    private static final String[] IDS={"minecraft:iron_ingot","minecraft:gold_ingot","minecraft:copper_ingot"};
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static CraftingRecipe recipe(RecipeManager manager,String id){return (CraftingRecipe)manager.byKey(ResourceLocation.parse("gtnh_audit:"+id)).orElseThrow().value();}
    private static List<CraftingRecipe> forms(CraftingRecipe recipe,RegistryOps<JsonElement> ops,RegistryAccess registries){
        JsonElement json=Recipe.CODEC.encodeStart(ops,recipe).getOrThrow();
        CraftingRecipe decoded=(CraftingRecipe)Recipe.CODEC.parse(ops,json).getOrThrow();
        RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
        CraftingRecipe network;
        try{Recipe.STREAM_CODEC.encode(buffer,recipe);network=(CraftingRecipe)Recipe.STREAM_CODEC.decode(buffer);}finally{buffer.release();}
        check(json.equals(Recipe.CODEC.encodeStart(ops,decoded).getOrThrow())&&json.equals(Recipe.CODEC.encodeStart(ops,network).getOrThrow()),"Shapeless codec changed recipe");
        return List.of(recipe,decoded,network);
    }
    private static CraftingInput input(int width,int height,int[] slots){
        List<ItemStack> values=new ArrayList<>();
        for(int id:slots)values.add(id<0?ItemStack.EMPTY:new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(IDS[id]))));
        return CraftingInput.of(width,height,values);
    }
    private static JsonArray ingredients(int[] choices){
        JsonArray values=new JsonArray();
        for(int mask:choices){JsonArray options=new JsonArray();for(int id=0;id<3;id++)if((mask&(1<<id))!=0){JsonObject value=new JsonObject();value.addProperty("item",IDS[id]);options.add(value);}values.add(options);}
        return values;
    }
    private static CraftingRecipe configured(JsonObject base,int[] choices,RegistryOps<JsonElement> ops){
        JsonObject value=base.deepCopy();value.add("ingredients",ingredients(choices));return (CraftingRecipe)Recipe.CODEC.parse(ops,value).getOrThrow();
    }
    private static ItemStack electric(String id,long charge){
        ItemStack stack=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();
        check(!stack.isEmpty(),"Missing actual shapeless electric input");
        stack.set(GTDataComponents.ENERGY_CONTENT,new SimpleEnergyContent(5_000_000_000L,charge,false,true));return stack;
    }
    public static JsonObject run(RecipeManager manager,RegistryOps<JsonElement> ops,RegistryAccess registries)throws Exception{
        CraftingRecipe base=recipe(manager,"shapeless_overlap");
        JsonObject encoded=Recipe.CODEC.encodeStart(ops,base).getOrThrow().getAsJsonObject();
        int comparisons=0,roundTrips=0;
        for(int length=0;length<=3;length++)for(int pattern=0;pattern<(int)Math.pow(7,length);pattern++){
            int value=pattern;int[] choices=new int[length];for(int i=0;i<length;i++){choices[i]=value%7+1;value/=7;}
            for(CraftingRecipe test:forms(configured(encoded,choices,ops),ops,registries))for(int a=-1;a<3;a++)for(int b=-1;b<3;b++)for(int c=-1;c<3;c++){
                int[] slots={a,b,c};boolean expected=ForgeShapelessReference.matches(choices,slots);
                check(test.matches(input(3,1,slots),null)==expected,"Legacy shapeless matching differs from fixed Forge source");comparisons++;
            }
            roundTrips+=2;
        }
        // The same two materials have different outcomes when the broad choice
        // consumes the sole iron slot first. A bipartite solver changes this.
        check(!base.matches(input(2,1,new int[]{0,1}),null)&&base.matches(input(2,1,new int[]{1,0}),null),"Shapeless greedy order witness failed");
        int boundaries=1;
        for(int[] choices:List.of(new int[]{0},new int[]{1,1,1,1,1,1,1,1,1,1})){
            int[] slots=new int[16];Arrays.fill(slots,-1);if(choices.length>1)Arrays.fill(slots,0,10,0);
            for(CraftingRecipe test:forms(configured(encoded,choices,ops),ops,registries)){
                check(test.matches(input(4,4,slots),null)==ForgeShapelessReference.matches(choices,slots),"Shapeless empty choice or extended-grid behavior changed");boundaries++;
            }
            roundTrips+=2;
        }
        ItemStack stacked=new ItemStack(Items.IRON_INGOT,64);
        check(!configured(encoded,new int[]{1,1},ops).matches(CraftingInput.of(1,1,List.of(stacked)),null),"Stack count replaced required slots");boundaries++;
        int[] wide={-1,-1,-1,1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,0};
        check(base.matches(input(4,4,wide),null),"Shapeless failed to scan whole original inventory");boundaries++;
        GT5ShapelessNbtReference.initialize();
        int nbtComparisons=0;
        List<byte[]> tags=NativeCraftingNbtCheck.values();
        for(CraftingRecipe test:forms(recipe(manager,"shapeless_nbt"),ops,registries))for(byte[] a:tags)for(byte[] b:tags){
            CraftingInput grid=CraftingInput.of(2,1,List.of(NativeCraftingNbtCheck.stack(a),NativeCraftingNbtCheck.stack(b)));
            ItemStack template=test.getResultItem(registries).copy();
            check(test.matches(grid,null)==GT5ShapelessNbtReference.matches(grid),"Shapeless NBT gate differs from GT5");
            byte[] expected=GT5ShapelessNbtReference.keep(grid,NativeCraftingNbtCheck.raw(template));
            ItemStack result=test.assemble(grid,registries);
            check(Arrays.equals(expected,NativeCraftingNbtCheck.raw(result)),"Shapeless NBT copy differs from GT5");
            ItemStack whole=template.copy();if(expected!=null)whole.set(LegacyItemTag.TYPE,new LegacyItemTag(expected));
            check(ItemStack.matches(whole,result),"Shapeless NBT assembly changed other output components");
            check(Arrays.equals(a,NativeCraftingNbtCheck.raw(grid.getItem(0)))&&Arrays.equals(b,NativeCraftingNbtCheck.raw(grid.getItem(1))),"Shapeless NBT assembly mutated input");
            check(ItemStack.matches(template,test.getResultItem(registries)),"Shapeless NBT assembly mutated template");nbtComparisons++;
        }
        roundTrips+=2;
        int energyComparisons=0;
        for(CraftingRecipe test:forms(recipe(manager,"shapeless_energy"),ops,registries))for(long a:new long[]{0,11,1_500_000_000L})for(long b:new long[]{0,17,1_500_000_000L})for(long c:new long[]{0,999,1_500_000_000L}){
            List<ItemStack> values=List.of(electric("gtceu:lv_lithium_battery",a),electric("gtceu:mv_lithium_battery",b),electric("gtceu:lv_steel_drill",c));
            List<ItemStack> before=values.stream().map(ItemStack::copy).toList();CraftingInput grid=CraftingInput.of(3,1,values);
            check(test.matches(grid,null),"Shapeless energy fixture did not match");
            ItemStack template=test.getResultItem(registries).copy();
            check(ItemStack.matches(GT5CraftingEnergyReference.apply(grid,template),test.assemble(grid,registries)),"Shapeless energy differs from GT5");
            for(int i=0;i<3;i++)check(ItemStack.matches(before.get(i),grid.getItem(i)),"Shapeless energy mutated input");
            check(ItemStack.matches(template,test.getResultItem(registries)),"Shapeless energy mutated template");energyComparisons++;
        }
        roundTrips+=2;
        CraftingRecipe bucket=recipe(manager,"shapeless_bucket");
        JsonObject keep=Recipe.CODEC.encodeStart(ops,bucket).getOrThrow().getAsJsonObject();
        keep.add("kubejs:ingredient_actions",Recipe.CODEC.encodeStart(ops,recipe(manager,"shape_keep")).getOrThrow().getAsJsonObject().get("kubejs:ingredient_actions"));
        int remainderChecks=0;
        for(boolean action:new boolean[]{false,true})for(CraftingRecipe test:forms(action?(CraftingRecipe)Recipe.CODEC.parse(ops,keep).getOrThrow():bucket,ops,registries)){
            CraftingInput grid=CraftingInput.of(2,1,List.of(new ItemStack(Items.MILK_BUCKET),new ItemStack(Items.IRON_INGOT)));
            check(test.matches(grid,null),"Shapeless remainder fixture did not match");
            var remaining=test.getRemainingItems(grid);
            check(remaining.get(0).is(action?Items.MILK_BUCKET:Items.BUCKET)&&remaining.get(0).getCount()==1&&remaining.get(1).isEmpty(),"Shapeless container/ingredient action changed");
            check(grid.getItem(0).is(Items.MILK_BUCKET),"Shapeless remainder mutated input");remainderChecks++;
        }
        roundTrips+=4;
        JsonObject report=new JsonObject();report.addProperty("source_match_comparisons",comparisons);report.addProperty("source_nbt_comparisons",nbtComparisons);
        report.addProperty("source_energy_comparisons",energyComparisons);report.addProperty("json_network_round_trips",roundTrips);
        report.addProperty("boundary_checks",boundaries);report.addProperty("remaining_item_checks",remainderChecks);
        report.addProperty("source_item_set_mapping_complete",false);report.addProperty("overwrite_and_custom_validator_complete",false);report.addProperty("world_or_player_executed",false);return report;
    }
}

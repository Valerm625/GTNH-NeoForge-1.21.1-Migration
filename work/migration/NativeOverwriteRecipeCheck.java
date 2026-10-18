package org.gtnhmigration.startupaudit;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.gtnhmigration.compat.*;

/** Installed recipe/component path against unchanged GT5 expressions and old NBT. */
public final class NativeOverwriteRecipeCheck {
    private static final String SOURCE = "fixture:old_tool";
    private static final int DAMAGE = 12;
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static ItemStack stack(Item modern,GT5OverwriteReference.Fixture source){
        if(source==null)return ItemStack.EMPTY;
        ItemStack result=new ItemStack(modern);
        result.set(LegacyItemIdentity.TYPE,new LegacyItemIdentity(source.item(),source.damage()));
        if(source.tag()!=null)result.set(LegacyItemTag.TYPE,new LegacyItemTag(source.tag()));
        return result;
    }
    private static List<CraftingRecipe> forms(JsonObject definition,RegistryOps<JsonElement> ops,RegistryAccess registries){
        CraftingRecipe original=(CraftingRecipe)Recipe.CODEC.parse(ops,definition).getOrThrow();
        JsonElement encoded=Recipe.CODEC.encodeStart(ops,original).getOrThrow();
        CraftingRecipe json=(CraftingRecipe)Recipe.CODEC.parse(ops,encoded).getOrThrow();
        RegistryFriendlyByteBuf buf=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
        CraftingRecipe network;
        try{Recipe.STREAM_CODEC.encode(buf,original);network=(CraftingRecipe)Recipe.STREAM_CODEC.decode(buf);}finally{buf.release();}
        check(encoded.equals(Recipe.CODEC.encodeStart(ops,network).getOrThrow()),"Overwrite recipe codec changed fields");
        return List.of(original,json,network);
    }
    private static JsonObject definition(JsonObject base,byte[] tag,boolean keep){
        JsonObject definition=base.deepCopy();definition.addProperty("gtnh:keep_legacy_nbt",keep);
        JsonObject components=definition.getAsJsonObject("result").getAsJsonObject("components");
        if(tag==null)components.remove("gtnh_migration:legacy_item_tag");
        else components.addProperty("gtnh_migration:legacy_item_tag",Base64.getEncoder().encodeToString(tag));
        return definition;
    }
    private static void compare(CraftingRecipe recipe,List<GT5OverwriteReference.Fixture> fixtures,byte[] templateTag,boolean keep,RegistryAccess registries){
        List<ItemStack> items=new ArrayList<>();
        for(int i=0;i<fixtures.size();i++)items.add(stack(i%2==0?Items.IRON_INGOT:Items.GOLD_INGOT,fixtures.get(i)));
        CraftingInput grid=CraftingInput.of(items.size(),1,items);
        List<ItemStack> before=grid.items().stream().map(ItemStack::copy).toList();
        ItemStack template=recipe.getResultItem(registries).copy();
        byte[] expected=GT5OverwriteReference.apply(fixtures,new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,templateTag),keep);
        ItemStack actual=recipe.assemble(grid,registries);
        check(Arrays.equals(expected,GT5OverwriteReference.canonical(NativeCraftingNbtCheck.raw(actual))),"Legacy overwrite differs from fixed GT5 source");
        ItemStack other=actual.copy();other.remove(LegacyItemTag.TYPE);ItemStack originalOther=template.copy();originalOther.remove(LegacyItemTag.TYPE);
        check(ItemStack.matches(other,originalOther),"Overwrite changed unrelated output components");
        check(ItemStack.matches(template,recipe.getResultItem(registries)),"Overwrite mutated result template");
        for(int i=0;i<before.size();i++)check(ItemStack.matches(before.get(i),grid.getItem(i)),"Overwrite mutated input");
        actual.remove(LegacyItemTag.TYPE);
        check(Arrays.equals(expected,GT5OverwriteReference.canonical(NativeCraftingNbtCheck.raw(recipe.assemble(grid,registries)))),"Overwrite output shared mutable state");
    }
    private static byte[] nested(boolean output)throws IOException{
        var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
        data.writeByte(10);data.writeUTF(output?"output-root":"input-root");
        data.writeByte(10);data.writeUTF("nested");
        data.writeByte(3);data.writeUTF(output?"new":"old");data.writeInt(output?2:1);data.writeByte(0);
        data.writeByte(5);data.writeUTF(output?"output-nan":"input-nan");data.writeInt(output?0x7fc00002:0x7fc00001);
        data.writeByte(0);return bytes.toByteArray();
    }
    public static JsonObject run(RecipeManager manager,RegistryOps<JsonElement> ops,RegistryAccess registries)throws Exception{
        GT5OverwriteReference.initialize();
        CraftingRecipe base=(CraftingRecipe)manager.byKey(ResourceLocation.parse("gtnh_audit:shapeless_overwrite")).orElseThrow().value();
        JsonObject encoded=Recipe.CODEC.encodeStart(ops,base).getOrThrow().getAsJsonObject();
        List<byte[]> tags=NativeCraftingNbtCheck.values();int comparisons=0,roundTrips=0,identityComparisons=0,itemRoundTrips=0,boundaries=0;
        byte[] first=nested(false),last=nested(true);
        for(boolean keep:new boolean[]{false,true})for(byte[] template:tags){
            for(CraftingRecipe recipe:forms(definition(encoded,template,keep),ops,registries))for(byte[] a:tags){
                compare(recipe,List.of(new GT5OverwriteReference.Fixture("fixture:other",0,first),new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,a),new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,last)),template,keep,registries);
                comparisons++;
            }
            roundTrips+=2;
        }
        // Same modern item/different old identity and different modern item/same
        // old identity are deliberately distinct fixtures, never actual mappings.
        int[] damages={Integer.MIN_VALUE,-1,0,DAMAGE,32767,32768,Integer.MAX_VALUE};
        String[] identities={SOURCE,"fixture:other","Fixture:old_tool"};
        for(String a:identities)for(int da:damages)for(String b:identities)for(int db:damages){
            var aa=new GT5OverwriteReference.Fixture(a,da,null);var bb=new GT5OverwriteReference.Fixture(b,db,null);
            check(new LegacyItemIdentity(a,da).sameItemAndDamage(new LegacyItemIdentity(b,db))==GT5OverwriteReference.sameIdentity(aa,bb),"Old item identity/wildcard rule changed");identityComparisons++;
        }
        for(String id:identities)for(int damage:damages){
            ItemStack original=stack(Items.IRON_INGOT,new GT5OverwriteReference.Fixture(id,damage,first));
            var nbtOps=RegistryOps.create(NbtOps.INSTANCE,registries);
            ItemStack json=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,original).getOrThrow()).getOrThrow();
            ItemStack nbt=ItemStack.CODEC.parse(nbtOps,ItemStack.CODEC.encodeStart(nbtOps,original).getOrThrow()).getOrThrow();
            RegistryFriendlyByteBuf buf=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);ItemStack network;
            try{ItemStack.STREAM_CODEC.encode(buf,original);network=ItemStack.STREAM_CODEC.decode(buf);}finally{buf.release();}
            for(ItemStack copy:List.of(json,nbt,network)){check(ItemStack.matches(original,copy),"Source identity/item tag changed during round trip");itemRoundTrips++;}
        }
        for(int damage:new int[]{DAMAGE,32767,13})for(CraftingRecipe recipe:forms(definition(encoded,last,false),ops,registries)){
            compare(recipe,Arrays.asList(null,new GT5OverwriteReference.Fixture(SOURCE,damage,first),new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,last)),last,false,registries);boundaries++;
        }
        roundTrips+=6;
        CraftingInput input=CraftingInput.of(3,1,List.of(stack(Items.IRON_INGOT,new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,first)),stack(Items.IRON_INGOT,new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,first)),stack(Items.IRON_INGOT,new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,first))));
        check(base.matches(input,null),"Overwrite recipe failed valid explicit identities");boundaries++;
        input.getItem(1).remove(LegacyItemIdentity.TYPE);
        check(!base.matches(input,null),"Overwrite inferred missing source identity");boundaries++;
        try{base.assemble(input,registries);throw new AssertionError("Missing source identity accepted in direct assembly");}catch(IllegalStateException expected){check(expected.getMessage().contains("explicit source identities"),"Wrong identity failure");}boundaries++;
        JsonObject disabled=encoded.deepCopy();disabled.addProperty("gtnh:overwrite_legacy_nbt",false);
        CraftingRecipe off=forms(disabled,ops,registries).getFirst();roundTrips+=2;
        check(off.matches(input,null)&&ItemStack.matches(off.getResultItem(registries),off.assemble(input,registries)),"Disabled overwrite changed matching or result");boundaries++;
        JsonObject missing=encoded.deepCopy();missing.getAsJsonObject("result").getAsJsonObject("components").remove("gtnh_migration:legacy_item_identity");
        CraftingRecipe missingOutput=forms(missing,ops,registries).getFirst();roundTrips+=2;
        input.getItem(1).set(LegacyItemIdentity.TYPE,new LegacyItemIdentity(SOURCE,DAMAGE));
        check(!missingOutput.matches(input,null),"Missing output source identity accepted");boundaries++;
        // Empty output is the modern representation of no crafting result.
        check(LegacyCraftingOverwrite.apply(input,ItemStack.EMPTY).isEmpty(),"Empty output required an identity");boundaries++;
        LegacyItemTag source=new LegacyItemTag(first),overrides=new LegacyItemTag(last),merged=source.withTopLevelOverrides(overrides);
        check(Arrays.equals(first,source.bytes())&&Arrays.equals(last,overrides.bytes()),"Top-level merge mutated components");boundaries++;
        // The selected raw float payloads survive even though the old serializer
        // canonicalizes NaNs in the independent semantic comparison.
        byte[] result=merged.bytes();String hex=HexFormat.of().formatHex(result);
        check(hex.contains("7fc00001")&&hex.contains("7fc00002"),"Overwrite normalized selected NaN payloads");boundaries++;
        for(CraftingRecipe recipe:forms(definition(encoded,last,false),ops,registries)){
            compare(recipe,Arrays.asList(null,new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,first),null),last,false,registries);boundaries++;
        }
        roundTrips+=2;
        for(CraftingRecipe recipe:forms(definition(encoded,last,false),ops,registries)){
            compare(recipe,Arrays.asList(null,new GT5OverwriteReference.Fixture("fixture:other",DAMAGE,first),null),last,false,registries);boundaries++;
            compare(recipe,Arrays.asList(null,new GT5OverwriteReference.Fixture(SOURCE,DAMAGE,null),null),last,false,registries);boundaries++;
        }
        roundTrips+=2;
        for(String invalid:List.of("{\"item\":\"\",\"damage\":0}","{\"item\":\"fixture:tool\"}","{\"damage\":0}")){
            check(LegacyItemIdentity.CODEC.parse(ops,JsonParser.parseString(invalid)).error().isPresent(),"Malformed source identity accepted");boundaries++;
        }
        JsonObject report=new JsonObject();report.addProperty("source_nbt_comparisons",comparisons);report.addProperty("source_identity_comparisons",identityComparisons);
        report.addProperty("recipe_round_trips",roundTrips);report.addProperty("item_round_trips",itemRoundTrips);report.addProperty("boundary_checks",boundaries);
        report.addProperty("original_1_7_10_nbt_objects",true);report.addProperty("automatic_source_identity_mapping_complete",false);
        report.addProperty("modern_component_mapping_complete",false);report.addProperty("item_updates_and_enchantments_complete",false);report.addProperty("world_or_player_executed",false);
        return report;
    }
}

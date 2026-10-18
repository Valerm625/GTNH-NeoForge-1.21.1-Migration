package org.gtnhmigration.startupaudit;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.gtnhmigration.compat.LegacyItemTag;

/** Real migration recipes/components with isolated original 1.7.10 NBT classes. */
public final class NativeCraftingNbtCheck {
    private interface Write { void write(DataOutputStream out) throws IOException; }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static byte[] tag(String name, Write fields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF(name); fields.write(out); out.writeByte(0);
        return bytes.toByteArray();
    }
    private static void field(DataOutputStream out, int type, String key, Write payload) throws IOException {
        out.writeByte(type); out.writeUTF(key); payload.write(out);
    }
    static ItemStack stack(byte[] raw) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        if (raw != null) stack.set(LegacyItemTag.TYPE, new LegacyItemTag(raw));
        return stack;
    }
    static byte[] raw(ItemStack stack) {
        LegacyItemTag value = stack.get(LegacyItemTag.TYPE); return value == null ? null : value.bytes();
    }
    static List<byte[]> values() throws IOException {
        List<byte[]> values = new ArrayList<>(); values.add(null); values.add(tag("", out -> {}));
        values.add(tag("tag", out -> {}));
        for (int type = 1; type <= 11; type++) {
            final int t = type;
            for (int variant = 0; variant < 2; variant++) {
                final int v = variant;
                values.add(tag("", out -> field(out, t, "value", data -> {
                    switch(t) {
                        case 1 -> data.writeByte(v);
                        case 2 -> data.writeShort(v);
                        case 3 -> data.writeInt(v);
                        case 4 -> data.writeLong(v == 0 ? 9_007_199_254_740_993L : Long.MIN_VALUE);
                        case 5 -> data.writeFloat(v);
                        case 6 -> data.writeDouble(v);
                        case 7 -> { data.writeInt(2); data.write(new byte[]{(byte)v, -128}); }
                        case 8 -> data.writeUTF(v == 0 ? "\u0000中文😀" : "different");
                        case 9 -> { data.writeByte(3); data.writeInt(1); data.writeInt(v); }
                        case 10 -> { field(data,3,"nested", n -> n.writeInt(v)); data.writeByte(0); }
                        case 11 -> { data.writeInt(2); data.writeInt(v); data.writeInt(Integer.MIN_VALUE); }
                        default -> throw new AssertionError();
                    }
                })));
            }
        }
        for (int bits : new int[]{0,0x80000000,0x7fc00001,0x7fc00002,0x7f800000,0xff800000})
            values.add(tag("", out -> field(out,5,"value", data -> data.writeInt(bits))));
        for (long bits : new long[]{0,Long.MIN_VALUE,0x7ff8000000000001L,0x7ff8000000000002L,0x7ff0000000000000L})
            values.add(tag("", out -> field(out,6,"value", data -> data.writeLong(bits))));
        for (int element : new int[]{0,1,3,10})
            values.add(tag("", out -> field(out,9,"empty", data -> { data.writeByte(element); data.writeInt(0); })));
        values.add(tag("", out -> { field(out,3,"a", n -> n.writeInt(1)); field(out,3,"b", n -> n.writeInt(2)); }));
        values.add(tag("ignored root", out -> { field(out,3,"b", n -> n.writeInt(2)); field(out,3,"a", n -> n.writeInt(1)); }));
        values.add(tag("", out -> { field(out,3,"value", n -> n.writeInt(999)); field(out,3,"value", n -> n.writeInt(1)); }));
        return values;
    }
    private static void compare(CraftingRecipe recipe, CraftingInput input, RegistryAccess registries) {
        List<ItemStack> before = input.items().stream().map(ItemStack::copy).toList();
        ItemStack template = recipe.getResultItem(registries).copy();
        check(recipe.matches(input,null) == GT5CraftingNbtReference.matches(input), "Legacy NBT match differs from fixed GT5 source");
        byte[] expected = GT5CraftingNbtReference.keep(input,raw(template));
        ItemStack result = recipe.assemble(input,registries);
        check(Arrays.equals(expected,raw(result)), "Legacy NBT copy differs from fixed GT5 source");
        check(ItemStack.matches(template,recipe.getResultItem(registries)), "NBT assembly mutated template");
        for (int i=0;i<input.size();i++) check(ItemStack.matches(before.get(i),input.getItem(i)), "NBT assembly mutated input");
        result.remove(LegacyItemTag.TYPE);
        check(Arrays.equals(expected,raw(recipe.assemble(input,registries))), "NBT result state shared with next craft");
    }
    public static JsonObject run(RecipeManager manager, RegistryOps<JsonElement> ops, RegistryAccess registries) throws Exception {
        GT5CraftingNbtReference.initialize();
        CraftingRecipe base=(CraftingRecipe)manager.byKey(ResourceLocation.parse("gtnh_audit:nbt_keep")).orElseThrow().value();
        JsonObject encoded=Recipe.CODEC.encodeStart(ops,base).getOrThrow().getAsJsonObject();
        List<byte[]> values=values(); int comparisons=0,recipeRoundTrips=0,itemRoundTrips=0;
        for (boolean shrink : new boolean[]{false,true}) {
            JsonObject definition=encoded.deepCopy(); definition.addProperty("kubejs:shrink",shrink);
            CraftingRecipe original=(CraftingRecipe)Recipe.CODEC.parse(ops,definition).getOrThrow();
            CraftingRecipe json=(CraftingRecipe)Recipe.CODEC.parse(ops,Recipe.CODEC.encodeStart(ops,original).getOrThrow()).getOrThrow();
            RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
            CraftingRecipe network;
            try { Recipe.STREAM_CODEC.encode(buffer,original); network=(CraftingRecipe)Recipe.STREAM_CODEC.decode(buffer); }
            finally { buffer.release(); }
            recipeRoundTrips+=2;
            for(CraftingRecipe recipe:List.of(original,json,network)) for(byte[] a:values) for(byte[] b:values) {
                compare(recipe,CraftingInput.of(2,1,List.of(stack(a),stack(b))),registries); comparisons++;
            }
        }
        for(byte[] value:values) {
            ItemStack item=stack(value);
            ItemStack json=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,item).getOrThrow()).getOrThrow();
            var nbtOps=RegistryOps.create(NbtOps.INSTANCE,registries);
            ItemStack nbt=ItemStack.CODEC.parse(nbtOps,ItemStack.CODEC.encodeStart(nbtOps,item).getOrThrow()).getOrThrow();
            RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),registries,ConnectionType.NEOFORGE);
            ItemStack network;
            try { ItemStack.STREAM_CODEC.encode(buffer,item); network=ItemStack.STREAM_CODEC.decode(buffer); }
            finally { buffer.release(); }
            for(ItemStack copy:List.of(json,nbt,network)) { check(Arrays.equals(value,raw(copy)),"Legacy tag bytes changed during item round trip"); itemRoundTrips++; }
            if(value!=null) { byte[] exposed=item.get(LegacyItemTag.TYPE).bytes(); exposed[0]=0; check(Arrays.equals(value,raw(item)),"Legacy tag exposed mutable bytes"); }
        }
        // No input tags retain the template. Assembly takes the first present
        // tag even if called directly on an input whose match would fail.
        CraftingRecipe disabled=(CraftingRecipe)manager.byKey(ResourceLocation.parse("gtnh_audit:nbt_disabled")).orElseThrow().value();
        CraftingInput mixed=CraftingInput.of(2,1,List.of(stack(null),stack(values.get(1))));
        check(disabled.matches(mixed,null),"Disabled NBT option checked input tags");
        check(ItemStack.matches(disabled.getResultItem(registries),disabled.assemble(mixed,registries)),"Disabled NBT option copied input tag");
        check(!base.matches(CraftingInput.of(2,1,List.of(new ItemStack(Items.GOLD_INGOT),stack(null))),null),"NBT gate bypassed ingredients");
        List<ItemStack> wide=new ArrayList<>(Collections.nCopies(16,ItemStack.EMPTY)); wide.set(0,stack(null));wide.set(1,stack(null));wide.set(15,stack(values.get(1)));
        check(!base.matches(CraftingInput.of(4,4,wide),null),"NBT gate skipped outlying nonempty slot");
        compare(base,mixed,registries);
        check(base.getResultItem(registries).get(DataComponents.CUSTOM_NAME)!=null,"NBT fixture lost unrelated output component");
        // Malformed, trailing, post-1.7 type, and negative sizes are rejected.
        List<byte[]> invalid=List.of(new byte[0],new byte[]{10,0,0},new byte[]{10,0,0,0,0},
            tag("", out -> field(out,12,"new_type", data -> data.writeInt(0))),
            tag("", out -> field(out,7,"negative", data -> data.writeInt(-1))),
            tag("", out -> field(out,9,"invalid_list", data -> {data.writeByte(0);data.writeInt(1);} )));
        for(byte[] bytes:invalid) { try { new LegacyItemTag(bytes); throw new AssertionError("Invalid legacy tag accepted"); } catch(IllegalArgumentException expected) {} }
        JsonObject report=new JsonObject(); report.addProperty("source_comparisons",comparisons);
        report.addProperty("tag_variants",values.size()); report.addProperty("recipe_round_trips",recipeRoundTrips);
        report.addProperty("item_round_trips",itemRoundTrips); report.addProperty("rejected_inputs",invalid.size());
        report.addProperty("original_1_7_10_nbt_classes",true);report.addProperty("modern_component_mapping_complete",false);
        report.addProperty("item_update_hooks_complete",false);report.addProperty("world_or_player_executed",false);
        return report;
    }
}

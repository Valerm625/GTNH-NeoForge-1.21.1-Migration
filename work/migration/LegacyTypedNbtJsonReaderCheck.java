import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyTypedNbtJsonReader;

/** Actual 1.21 NBT plus the hash-pinned BQ JSON decoder oracle. No game/world. */
public final class LegacyTypedNbtJsonReaderCheck {
    static int assertions, comparisons, rejected;
    static void require(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    static LegacyTypedNbtJsonReader.Result inspect(String json) {
        return LegacyTypedNbtJsonReader.inspect(json.getBytes(StandardCharsets.UTF_8), "synthetic");
    }
    static CompoundTag accepted(String json) {
        var result = inspect(json);
        require(result.accepted(), "Supported fixture rejected: " + result.diagnostics());
        require(result.diagnostics().isEmpty(), "Accepted result contains diagnostics");
        CompoundTag actual = result.nbt().orElseThrow();
        CompoundTag original = BqTypedJsonReference.read(json);
        require(actual.equals(original), "Pinned decoder disagreement on supported fixture");
        require(BqTypedJsonReference.errors == 0, "Pinned decoder used error fallback");
        comparisons++;
        return actual;
    }
    static void reject(String json, String code) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        var result = LegacyTypedNbtJsonReader.inspect(raw, "synthetic-rejected");
        require(!result.accepted() && result.nbt().isEmpty(), "Rejected fixture exposed partial NBT");
        require(Arrays.equals(raw, result.rawUtf8()), "Rejected raw input lost");
        require(result.diagnostics().size() == 1, "Missing diagnostic");
        if (code != null) require(result.diagnostics().getFirst().code().equals(code), "Unexpected diagnostic: " + result.diagnostics());
        rejected++;
    }
    static void scalars() throws Exception {
        CompoundTag all = accepted("{\"b:1\":-128,\"s:2\":-32768,\"i:3\":-2147483648,\"l:4\":-9223372036854775808,\"f:5\":1.25,\"d:6\":2.5,\"a:7\":[-128,0,127],\"text:8\":\"中文\\u0000\\n😀\",\"list:9\":{\"9:3\":9,\"0:3\":0},\"obj:10\":{\"namespace:name:8\":\"ok\"},\"ints:11\":[-2147483648,2147483647]}");
        for (int type = 1; type <= 11; type++) {
            final int id = type;
            require(all.getAllKeys().stream().map(all::get).anyMatch(t -> t.getId() == id), "Missing actual NBT type " + id);
        }
        require(all.getLong("l") == Long.MIN_VALUE, "Lost full signed 64-bit integer");
        require(all.getList("list", 3).getInt(0) == 9 && all.getList("list", 3).getInt(1) == 0, "List keys were numerically sorted");
        require(all.getCompound("obj").getString("namespace:name").equals("ok"), "Did not split final colon only");
        accepted("{\"b:1\":true,\"false:1\":false,\"s:2\":32767,\"i:3\":2147483647,\"l:4\":9223372036854775807}");
        for (int i = -128; i <= 127; i++) accepted("{\"v:1\":" + i + "}");
        Random random = new Random(2842101L);
        for (int i = 0; i < 300; i++) {
            long value = random.nextLong();
            require(accepted("{\"v:4\":" + value + "}").getLong("v") == value, "Random long lost precision");
        }
        float[] floats = {Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE, -Float.MAX_VALUE, 0.1f, 0.0f};
        for (float value : floats) require(Float.floatToRawIntBits(accepted("{\"v:5\":" + Float.toString(value) + "}").getFloat("v")) == Float.floatToRawIntBits(value), "Float raw bits differ");
        double[] doubles = {Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE, 0.1, 0.0};
        for (double value : doubles) require(Double.doubleToRawLongBits(accepted("{\"v:6\":" + Double.toString(value) + "}").getDouble("v")) == Double.doubleToRawLongBits(value), "Double raw bits differ");
        require(Float.floatToRawIntBits(FloatTag.valueOf(-0.0f).getAsFloat()) == 0, "Modern float negative-zero limitation changed");
        require(Double.doubleToRawLongBits(DoubleTag.valueOf(-0.0d).getAsDouble()) == 0, "Modern double negative-zero limitation changed");
        var floatInput = new java.io.DataInputStream(new java.io.ByteArrayInputStream(java.nio.ByteBuffer.allocate(4).putFloat(-0.0f).array()));
        var doubleInput = new java.io.DataInputStream(new java.io.ByteArrayInputStream(java.nio.ByteBuffer.allocate(8).putDouble(-0.0d).array()));
        require(Float.floatToRawIntBits(FloatTag.TYPE.load(floatInput, NbtAccounter.unlimitedHeap()).getAsFloat()) == 0, "Modern float loader negative-zero limitation changed");
        require(Double.doubleToRawLongBits(DoubleTag.TYPE.load(doubleInput, NbtAccounter.unlimitedHeap()).getAsDouble()) == 0, "Modern double loader negative-zero limitation changed");
        reject("{\"v:5\":-0.0}", "MODERN_NBT_NEGATIVE_ZERO");
        reject("{\"v:6\":-0.0}", "MODERN_NBT_NEGATIVE_ZERO");
        reject("{\"v:5\":1e-99}", "FLOAT_UNDERFLOW");
        reject("{\"v:6\":-1e-999}", "DOUBLE_UNDERFLOW");
        reject("{\"v:5\":1e99}", "FLOAT_OVERFLOW");
        reject("{\"v:6\":1e999}", "DOUBLE_OVERFLOW");
    }
    static void malformed() {
        reject("{\"v:3\":1,\"v:3\":2}", "DUPLICATE_JSON_KEY");
        reject("{\"v:3\":1,\"v:4\":2}", "STRIPPED_KEY_COLLISION");
        reject("{\"v:3\":1,\"\\u0076:3\":2}", "DUPLICATE_JSON_KEY");
        reject("{\"v:9\":{\"0:3\":1,\"1:4\":2}}", "MIXED_LIST_TYPES");
        reject("{\"v:9\":{\"0:3\":1,\"0:3\":2}}", "DUPLICATE_JSON_KEY");
        reject("{\"v:9\":{\"0:3\":1,\"0:4\":2}}", "STRIPPED_KEY_COLLISION");
        reject("{\"v:10\":{\"inner:3\":1,\"inner:4\":2}}", "STRIPPED_KEY_COLLISION");
        for (String suffix : List.of("0", "-1", "12", "99", "128", "01", "+1", "", "unknown"))
            reject("{\"v:" + suffix + "\":{}}", "UNSUPPORTED_TAG_ID");
        reject("{\"v\":1}", "MISSING_TAG_SUFFIX");
        for (String pair : List.of("1:128", "1:-129", "2:32768", "2:-32769", "3:2147483648", "3:-2147483649", "4:9223372036854775808", "4:-9223372036854775809")) {
            String[] parts = pair.split(":"); reject("{\"v:" + parts[0] + "\":" + parts[1] + "}", "INTEGER_OVERFLOW");
        }
        for (int id = 1; id <= 4; id++) {
            reject("{\"v:" + id + "\":1.0}", "NON_INTEGER_TOKEN");
            reject("{\"v:" + id + "\":1e0}", "NON_INTEGER_TOKEN");
            reject("{\"v:" + id + "\":\"1\"}", "WRONG_JSON_TYPE");
        }
        reject("{\"v:7\":[128]}", "INTEGER_OVERFLOW");
        reject("{\"v:11\":[2147483648]}", "INTEGER_OVERFLOW");
        reject("{\"v:7\":[1.5]}", "NON_INTEGER_TOKEN");
        reject("{\"v:11\":[true]}", "WRONG_JSON_TYPE");
        reject("{\"v:8\":2}", "WRONG_JSON_TYPE");
        reject("{\"v:9\":[]}", "WRONG_JSON_TYPE");
        reject("{\"v:3\":null}", "WRONG_JSON_TYPE");
        for (String text : List.of("", "[]", "null", "{", "{}{}", "{\"v:3\":01}", "{\"v:3\":NaN}", "{\"v:3\":Infinity}", "{/*comment*/}", "{\"v:3\":1,}", "{'v:3':1}", "{v:3}", "{} trailing")) reject(text, null);
        reject("{\"v:8\":\"raw\nnewline\"}", null);
        reject("{\"v:1\":TRUE}", null);
        reject("{\"v:8\":\"bad\\'escape\"}", "INVALID_JSON_ESCAPE");
        reject("\ufeff{}", "UNSUPPORTED_BOM");
        byte[][] invalid = {{(byte)0xc0,(byte)0xaf}, {(byte)0xe2,(byte)0x82}, {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xff}};
        for (byte[] raw : invalid) {
            var result = LegacyTypedNbtJsonReader.inspect(raw, "invalid-utf8");
            require(!result.accepted() && result.nbt().isEmpty() && result.diagnostics().getFirst().code().equals("INVALID_UTF8"), "Invalid UTF8 accepted");
            require(Arrays.equals(raw, result.rawUtf8()), "Invalid UTF8 bytes lost"); rejected++;
        }
        reject("{\"v:4\":" + "1".repeat(129) + "}", "NUMERIC_TOKEN_LIMIT");
        accepted("{\"v:10\":".repeat(127) + "{\"x:3\":1}" + "}".repeat(127));
        reject("{\"v:10\":".repeat(128) + "{\"x:3\":1}" + "}".repeat(128), "DEPTH_LIMIT");
        byte[] large = new byte[LegacyTypedNbtJsonReader.MAX_INPUT_BYTES + 1];
        var limited = LegacyTypedNbtJsonReader.inspect(large, "oversized");
        require(!limited.accepted() && limited.diagnostics().getFirst().code().equals("INPUT_LIMIT") && Arrays.equals(large, limited.rawUtf8()), "Input limit lost raw input");
        reject("{\"v:11\":[" + "0,".repeat(LegacyTypedNbtJsonReader.MAX_NODES) + "0]}", "NODE_LIMIT");
    }
    static void containersAndCopies() {
        accepted("{\"emptyList:9\":{},\"emptyCompound:10\":{},\"emptyByteArray:7\":[],\"emptyIntArray:11\":[],\"nested:9\":{\"2:9\":{\"x:8\":\"second\"},\"1:9\":{\"z:8\":\"first\"}}}");
        accepted("{\"bytes:9\":{\"0:7\":[1],\"1:7\":[2]},\"ints:9\":{\"0:11\":[3],\"1:11\":[4]},\"compounds:9\":{\"1:10\":{\"v:3\":1},\"0:10\":{\"v:3\":2}}}");
        String json = "{\"z:3\":3,\"a/b~c:10\":{\"second:8\":\"two\",\"first:8\":\"one\"},\"a:3\":1}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        var result = LegacyTypedNbtJsonReader.inspect(raw, "order-and-copies");
        require(result.accepted() && result.sourceIdentity().equals("order-and-copies"), "Identity not retained");
        require(result.memberOrder().get("").equals(List.of("z:3", "a/b~c:10", "a:3")), "Compound encounter order lost");
        require(result.memberOrder().get("/a~1b~0c:10").equals(List.of("second:8", "first:8")), "Nested order or pointer escaping lost");
        raw[0] = '!'; byte[] returned = result.rawUtf8(); returned[0] = '!';
        require(result.rawUtf8()[0] == '{', "Raw bytes not defensive");
        CompoundTag changed = result.nbt().orElseThrow(); changed.getCompound("a/b~c").putInt("mutated", 7);
        require(!result.nbt().orElseThrow().getCompound("a/b~c").contains("mutated"), "Returned NBT not defensive");
        try { result.memberOrder().get("").add("mutation"); throw new AssertionError("Mutable names"); } catch (UnsupportedOperationException expected) { assertions++; }
        try { result.memberOrder().clear(); throw new AssertionError("Mutable ledger"); } catch (UnsupportedOperationException expected) { assertions++; }
    }
    static void realDefinitions(String[] args) throws Exception {
        require(args.length == 2, "Need the two hash-verified definition fixture paths");
        long[][] ids = {{0, 2}, {4300463009256719073L, -7881005862838025770L}};
        for (int i = 0; i < 2; i++) {
            byte[] bytes = Files.readAllBytes(Path.of(args[i]));
            var result = LegacyTypedNbtJsonReader.inspect(bytes, "real-definition-" + i);
            CompoundTag nbt = accepted(new String(bytes, StandardCharsets.UTF_8));
            require(result.accepted() && Arrays.equals(result.rawUtf8(), bytes), "Definition raw bytes lost");
            require(nbt.contains("questIDHigh", 4) && nbt.contains("questIDLow", 4), "UUID halves changed type");
            require(nbt.getLong("questIDHigh") == ids[i][0] && nbt.getLong("questIDLow") == ids[i][1], "Definition UUID lost 64-bit precision");
            ListTag tasks = nbt.getList("tasks", 10);
            require(!tasks.isEmpty(), "Real definition lost tasks");
            if (i == 0) {
                require(tasks.size() == 2 && tasks.getCompound(0).getInt("index") == 0 && tasks.getCompound(1).getInt("index") == 1, "Definition task indexes changed");
                require(tasks.getCompound(0).getString("taskID").equals("bq_standard:retrieval"), "Definition factory changed");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        scalars(); malformed(); containersAndCopies(); realDefinitions(args);
        System.out.println("{\"test\":\"legacy_typed_nbt_json_reader\",\"passed\":true,\"assertions\":" + assertions
            + ",\"pinned_source_decoder_comparisons\":" + comparisons + ",\"rejected_fixtures\":" + rejected
            + ",\"real_task_definition_fixtures\":2,\"real_player_save_fixtures\":0,\"actual_1_21_nbt_executed\":true,\"negative_zero_blocked_with_raw_retained\":true,\"world_or_storage_integration\":false}");
    }
}

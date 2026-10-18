package org.gtnhmigration.compat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.io.*;
import java.util.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Explicit legacy item tag, not an alias for modern CUSTOM_DATA.
 * Stores the original uncompressed named compound, including floating point
 * bits and empty-list element types. Native item behavior needs its own mapping.
 */
public final class LegacyItemTag {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final Codec<LegacyItemTag> CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            if (value.length() > ((MAX_BYTES + 2L) / 3) * 4) throw new IllegalArgumentException("Legacy tag too large");
            return DataResult.success(new LegacyItemTag(Base64.getDecoder().decode(value)));
        } catch (IllegalArgumentException error) {
            return DataResult.error(() -> "Invalid legacy item tag: " + error.getMessage());
        }
    }, value -> Base64.getEncoder().encodeToString(value.raw));
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "gtnh_migration");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LegacyItemTag>> TYPE = COMPONENTS.registerComponentType("legacy_item_tag",
            builder -> builder.persistent(CODEC).networkSynchronized(ByteBufCodecs.byteArray(MAX_BYTES).map(LegacyItemTag::new, LegacyItemTag::bytes)));
    private final byte[] raw;
    private final Node root;

    public static void register(IEventBus bus) { COMPONENTS.register(bus); }

    public LegacyItemTag(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Legacy tag too large");
        raw = bytes.clone();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Root is not a compound");
            input.readUTF();
            root = read(input, 10, 0);
            if (input.available() != 0) throw new IOException("Trailing bytes");
        } catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }

    public byte[] bytes() { return raw.clone(); }
    /** Separate from component equality: legacy numeric NaNs are not equal.
     * Compare independent decoded trees even if the immutable component is shared.
     */
    public boolean sameTag(LegacyItemTag other) { return other != null && root.same(other.root); }
    @Override public boolean equals(Object other) { return other instanceof LegacyItemTag value && Arrays.equals(raw, value.raw); }
    @Override public int hashCode() { return Arrays.hashCode(raw); }

    private record Sequence(int elementType, List<Node> values) {}
    private record Node(int type, Object value) {
        @SuppressWarnings("unchecked")
        boolean same(Node other) {
            if (type != other.type) return false;
            return switch (type) {
                case 5 -> ((Float)value).floatValue() == ((Float)other.value).floatValue();
                case 6 -> ((Double)value).doubleValue() == ((Double)other.value).doubleValue();
                case 7 -> Arrays.equals((byte[])value, (byte[])other.value);
                case 11 -> Arrays.equals((int[])value, (int[])other.value);
                case 9 -> {
                    Sequence a = (Sequence)value, b = (Sequence)other.value;
                    boolean equal = a.elementType == b.elementType && a.values.size() == b.values.size();
                    for (int i = 0; equal && i < a.values.size(); i++) equal = a.values.get(i).same(b.values.get(i));
                    yield equal;
                }
                case 10 -> {
                    Map<String,Node> a = (Map<String,Node>)value, b = (Map<String,Node>)other.value;
                    boolean equal = a.keySet().equals(b.keySet());
                    for (String key : a.keySet()) { if (!equal) break; equal = a.get(key).same(b.get(key)); }
                    yield equal;
                }
                default -> value.equals(other.value);
            };
        }
    }

    private static int count(DataInputStream input, int width) throws IOException {
        int count = input.readInt();
        if (count < 0 || (long)count * width > input.available()) throw new IOException("Invalid NBT length");
        return count;
    }
    private static Node read(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nesting limit exceeded");
        Object value = switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> Float.intBitsToFloat(input.readInt());
            case 6 -> Double.longBitsToDouble(input.readLong());
            case 7 -> { byte[] values = new byte[count(input, 1)]; input.readFully(values); yield values; }
            case 8 -> input.readUTF();
            case 9 -> {
                int child = input.readUnsignedByte(), size = count(input, 1);
                if (child > 11 || (child == 0 && size != 0)) throw new IOException("Invalid legacy list type");
                List<Node> values = new ArrayList<>();
                for (int i = 0; i < size; i++) values.add(read(input, child, depth + 1));
                yield new Sequence(child, List.copyOf(values));
            }
            case 10 -> {
                Map<String,Node> values = new HashMap<>();
                for (int child; (child = input.readUnsignedByte()) != 0;) {
                    String key = input.readUTF();
                    values.put(key, read(input, child, depth + 1));
                }
                yield Collections.unmodifiableMap(values);
            }
            case 11 -> { int[] values = new int[count(input, 4)]; for (int i = 0; i < values.length; i++) values[i] = input.readInt(); yield values; }
            default -> throw new IOException("Unknown legacy NBT type");
        };
        return new Node(type, value);
    }
}

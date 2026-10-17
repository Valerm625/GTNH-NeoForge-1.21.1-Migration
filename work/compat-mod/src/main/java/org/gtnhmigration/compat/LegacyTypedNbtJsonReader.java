package org.gtnhmigration.compat;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.nbt.*;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict, read-only subset of BQ 3.8.89 format=true JSON. Never activates a save.
 * IDs 1..11 are explicit; ID 0 is old type inference, not EndTag support.
 * Original bytes and JSON member order are retained separately from modern NBT.
 */
public final class LegacyTypedNbtJsonReader {
    public static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DEPTH = 128;
    public static final int MAX_NODES = 1_000_000;
    private LegacyTypedNbtJsonReader() {}

    public record Diagnostic(String code, String path) {}

    public static final class Result {
        private final byte[] raw;
        private final String sourceIdentity;
        private final CompoundTag nbt;
        private final List<Diagnostic> diagnostics;
        private final Map<String,List<String>> memberOrder;
        private Result(byte[] raw, String sourceIdentity, CompoundTag nbt,
                       List<Diagnostic> diagnostics, Map<String,List<String>> order) {
            this.raw = raw.clone();
            this.sourceIdentity = sourceIdentity;
            this.nbt = nbt == null ? null : nbt.copy();
            this.diagnostics = List.copyOf(diagnostics);
            Map<String,List<String>> copy = new LinkedHashMap<>();
            order.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            this.memberOrder = Collections.unmodifiableMap(copy);
        }
        public byte[] rawUtf8() { return raw.clone(); }
        public String sourceIdentity() { return sourceIdentity; }
        /** Typed decoding succeeded only; identity, schema and activation need separate checks. */
        public boolean accepted() { return nbt != null && diagnostics.isEmpty(); }
        public Optional<CompoundTag> nbt() { return nbt == null ? Optional.empty() : Optional.of(nbt.copy()); }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        /** JSON Pointer container paths -> member names in original encounter order. */
        public Map<String,List<String>> memberOrder() { return memberOrder; }
    }

    public static Result inspect(byte[] rawUtf8, String sourceIdentity) {
        Objects.requireNonNull(rawUtf8, "rawUtf8");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        byte[] raw = rawUtf8.clone();
        Map<String,List<String>> order = new LinkedHashMap<>();
        if (raw.length > MAX_INPUT_BYTES) return rejected(raw, sourceIdentity, "INPUT_LIMIT", "", order);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException error) {
            return rejected(raw, sourceIdentity, "INVALID_UTF8", "", order);
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            strictLexemes(text);
            reader.setLenient(false);
            Parser parser = new Parser(reader, order);
            CompoundTag nbt = (CompoundTag) parser.read(10, "", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Rejected("TRAILING_JSON", "");
            return new Result(raw, sourceIdentity, nbt, List.of(), order);
        } catch (Rejected error) {
            return rejected(raw, sourceIdentity, error.code, error.path, order);
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            // Do not put Gson's source excerpts or numeric lexemes into diagnostics.
            return rejected(raw, sourceIdentity, "MALFORMED_JSON", "", order);
        }
    }

    private static Result rejected(byte[] raw, String identity, String code, String path,
                                   Map<String,List<String>> order) {
        return new Result(raw, identity, null, List.of(new Diagnostic(code, path)), order);
    }

    /** Gson 2.10.1's non-lenient mode still accepts raw control characters in
     * strings and uppercase literals. Reject those before its streaming parser;
     * this scan does not build an object tree or erase duplicate properties.
     */
    private static void strictLexemes(String text) throws Rejected {
        boolean string = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (string) {
                if (ch == '"') string = false;
                else if (ch < 0x20) throw new Rejected("UNESCAPED_CONTROL", "");
                else if (ch == '\\') {
                    if (++i >= text.length()) throw new Rejected("INVALID_JSON_ESCAPE", "");
                    char escaped = text.charAt(i);
                    if (escaped == 'u') {
                        for (int n = 0; n < 4; n++) {
                            if (++i >= text.length()) throw new Rejected("INVALID_JSON_ESCAPE", "");
                            char hex = text.charAt(i);
                            if (!(hex >= '0' && hex <= '9' || hex >= 'a' && hex <= 'f' || hex >= 'A' && hex <= 'F'))
                                throw new Rejected("INVALID_JSON_ESCAPE", "");
                        }
                    } else if ("\"\\/bfnrt".indexOf(escaped) < 0) throw new Rejected("INVALID_JSON_ESCAPE", "");
                }
            } else if (ch == '"') string = true;
            else if (ch == '\ufeff') throw new Rejected("UNSUPPORTED_BOM", "");
            else if (ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z') {
                // e/E is part of a JSON number; the streaming numeric grammar checks it.
                if ((ch == 'e' || ch == 'E') && i > 0 && Character.isDigit(text.charAt(i - 1))) continue;
                int start = i;
                while (i + 1 < text.length() && Character.isLetter(text.charAt(i + 1))) i++;
                String token = text.substring(start, i + 1);
                if (!token.equals("true") && !token.equals("false") && !token.equals("null"))
                    throw new Rejected("NONCANONICAL_JSON_LITERAL", "");
            }
        }
    }

    private static final class Rejected extends Exception {
        final String code, path;
        Rejected(String code, String path) { this.code = code; this.path = path; }
    }

    private static final class Parser {
        final JsonReader reader;
        final Map<String,List<String>> order;
        int nodes;
        Parser(JsonReader reader, Map<String,List<String>> order) { this.reader = reader; this.order = order; }
        void expect(JsonToken token, String path) throws IOException, Rejected {
            if (reader.peek() != token) throw new Rejected("WRONG_JSON_TYPE", path);
        }
        void node(String path, int depth) throws Rejected {
            if (depth > MAX_DEPTH) throw new Rejected("DEPTH_LIMIT", path);
            if (++nodes > MAX_NODES) throw new Rejected("NODE_LIMIT", path);
        }
        Tag read(int id, String path, int depth) throws IOException, Rejected {
            node(path, depth);
            if (id == 9 || id == 10) return object(id == 9, path, depth);
            if (id == 8) { expect(JsonToken.STRING, path); return StringTag.valueOf(reader.nextString()); }
            if (id == 7 || id == 11) {
                expect(JsonToken.BEGIN_ARRAY, path); reader.beginArray();
                List<Integer> values = new ArrayList<>();
                while (reader.hasNext()) {
                    String child = path + "/" + values.size(); node(child, depth + 1);
                    values.add(integer(id == 7 ? 1 : 3, child).intValue());
                }
                reader.endArray();
                if (id == 11) return new IntArrayTag(values.stream().mapToInt(Integer::intValue).toArray());
                byte[] bytes = new byte[values.size()];
                for (int i = 0; i < bytes.length; i++) bytes[i] = values.get(i).byteValue();
                return new ByteArrayTag(bytes);
            }
            if (id == 1 && reader.peek() == JsonToken.BOOLEAN) return ByteTag.valueOf(reader.nextBoolean());
            if (id >= 1 && id <= 4) {
                BigInteger value = integer(id, path);
                return switch (id) {
                    case 1 -> ByteTag.valueOf(value.byteValueExact());
                    case 2 -> ShortTag.valueOf(value.shortValueExact());
                    case 3 -> IntTag.valueOf(value.intValueExact());
                    default -> LongTag.valueOf(value.longValueExact());
                };
            }
            if (id == 5 || id == 6) {
                String token = number(path);
                BigDecimal decimal = new BigDecimal(token);
                if (id == 5) {
                    float value = Float.parseFloat(token);
                    if (!Float.isFinite(value)) throw new Rejected("FLOAT_OVERFLOW", path);
                    if (value == 0 && decimal.signum() != 0) throw new Rejected("FLOAT_UNDERFLOW", path);
                    if (Float.floatToRawIntBits(value) == 0x80000000) throw new Rejected("MODERN_NBT_NEGATIVE_ZERO", path);
                    return FloatTag.valueOf(value);
                }
                double value = Double.parseDouble(token);
                if (!Double.isFinite(value)) throw new Rejected("DOUBLE_OVERFLOW", path);
                if (value == 0 && decimal.signum() != 0) throw new Rejected("DOUBLE_UNDERFLOW", path);
                if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) throw new Rejected("MODERN_NBT_NEGATIVE_ZERO", path);
                return DoubleTag.valueOf(value);
            }
            throw new Rejected("UNSUPPORTED_TAG_ID", path);
        }
        String number(String path) throws IOException, Rejected {
            expect(JsonToken.NUMBER, path);
            String value = reader.nextString();
            if (value.length() > 128) throw new Rejected("NUMERIC_TOKEN_LIMIT", path);
            return value;
        }
        BigInteger integer(int id, String path) throws IOException, Rejected {
            String token = number(path);
            if (!token.matches("-?(0|[1-9][0-9]*)")) throw new Rejected("NON_INTEGER_TOKEN", path);
            BigInteger value = new BigInteger(token);
            int bits = switch (id) { case 1 -> 8; case 2 -> 16; case 3 -> 32; default -> 64; };
            if (value.compareTo(BigInteger.ONE.shiftLeft(bits - 1).negate()) < 0
                || value.compareTo(BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)) > 0)
                throw new Rejected("INTEGER_OVERFLOW", path);
            return value;
        }
        Tag object(boolean list, String path, int depth) throws IOException, Rejected {
            expect(JsonToken.BEGIN_OBJECT, path); reader.beginObject();
            CompoundTag compound = new CompoundTag();
            ListTag result = new ListTag();
            Set<String> rawKeys = new HashSet<>(), strippedKeys = new HashSet<>();
            List<String> names = new ArrayList<>(); order.put(path, names);
            int elementType = 0;
            while (reader.hasNext()) {
                String key = reader.nextName();
                String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
                names.add(key);
                if (!rawKeys.add(key)) throw new Rejected("DUPLICATE_JSON_KEY", child);
                int colon = key.lastIndexOf(':');
                if (colon < 0) throw new Rejected("MISSING_TAG_SUFFIX", child);
                String suffix = key.substring(colon + 1);
                if (!suffix.matches("([1-9]|10|11)")) throw new Rejected("UNSUPPORTED_TAG_ID", child);
                int id = Integer.parseInt(suffix);
                String plain = key.substring(0, colon);
                if (!strippedKeys.add(plain)) throw new Rejected("STRIPPED_KEY_COLLISION", child);
                Tag tag = read(id, child, depth + 1);
                if (list) {
                    if (elementType != 0 && id != elementType) throw new Rejected("MIXED_LIST_TYPES", child);
                    elementType = id;
                    if (!result.add(tag)) throw new Rejected("LIST_ELEMENT_REJECTED", child);
                } else compound.put(plain, tag);
            }
            reader.endObject();
            return list ? result : compound;
        }
    }
}

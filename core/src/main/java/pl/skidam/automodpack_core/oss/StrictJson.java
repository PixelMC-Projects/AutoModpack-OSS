package pl.skidam.automodpack_core.oss;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Bounded JSON with duplicate-member rejection; signed bytes are never reserialized. */
final class StrictJson {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private StrictJson() {}
    static JsonObject object(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("JSON exceeds limit");
        var decoder = StandardCharsets.UTF_8.newDecoder();
        try (var reader = new JsonReader(new StringReader(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()))) {
            reader.setLenient(false); // Gson 2.10 bundled by Minecraft 1.20.1 must remain supported.
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject()) throw new IOException("Expected JSON object");
            return result.getAsJsonObject();
        } catch (RuntimeException ex) { throw new IOException("Invalid JSON", ex); }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 24) throw new IOException("JSON nesting limit exceeded");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); var names = new HashSet<String>(); reader.beginObject();
                while (reader.hasNext()) { String name = reader.nextName(); if (!names.add(name)) throw new IOException("Duplicate JSON member"); object.add(name, read(reader, depth + 1)); }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) { if (array.size() > 10000) throw new IOException("Array limit exceeded"); array.add(read(reader, depth + 1)); }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> { String value = reader.nextString(); try { yield new JsonPrimitive(new java.math.BigDecimal(value)); } catch (NumberFormatException ex) { throw new IOException("Invalid number", ex); } }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Unexpected JSON token");
        };
    }
    static String string(JsonObject o, String name) throws IOException {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IOException("Missing/invalid " + name);
        return e.getAsString();
    }
    static long number(JsonObject o, String name) throws IOException {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IOException("Missing/invalid " + name);
        try { return e.getAsBigDecimal().longValueExact(); } catch (ArithmeticException ex) { throw new IOException("Invalid integer " + name, ex); }
    }
    static JsonArray array(JsonObject o, String name) throws IOException {
        JsonElement e = o.get(name); if (e == null || !e.isJsonArray()) throw new IOException("Missing/invalid " + name); return e.getAsJsonArray();
    }
    static byte[] bytes(Object value) { return GSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
}

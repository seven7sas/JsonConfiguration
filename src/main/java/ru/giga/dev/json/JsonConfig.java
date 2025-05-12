package ru.giga.dev.json;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * Class for working with JSON configuration files with support for complex paths.
 * <p>
 * Supported path syntax:
 * - Objects: {@code "key.sub_key"}
 * - Arrays: {@code "array.list[3]"}
 */
public class JsonConfig {

    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\w+)\\[(\\d+)]");
    private final static Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private final File file;
    private final File folder;
    private final JsonElement root;
    private final Getter getter;
    private final Setter setter;
    private Gson gson;

    /**
     * Creates a new configuration instance.
     *
     * @param file JSON file to work with (created automatically if not exists)
     * @throws IOException         if there's an error reading/creating the file
     * @throws JsonSyntaxException if there's a syntax error in the JSON
     */
    public JsonConfig(File file) throws IOException {
        this.file = file;
        this.folder = file.getParentFile();

        if (!folder.exists()) folder.mkdirs();
        if (!file.exists()) file.createNewFile();

        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(file)))) {
            JsonElement parsed = JsonParser.parseReader(reader);
            this.root = parsed.isJsonObject() ? parsed : new JsonObject();
            this.getter = new Getter(root);
            this.setter = new Setter(root);
        } catch (Exception e) {
            LogUtils.getLogger().error("Failed to parse json in {}", file);
            throw new RuntimeException(e);
        }
        this.gson = GSON;
    }

    /**
     * Creates a new JsonConfig instance with a custom Gson instance.
     *
     * @param file JSON file to work with (created automatically if not exists)
     * @param gson Custom Gson instance for serialization/deserialization
     * @throws IOException         if there's an error reading/creating the file
     * @throws JsonSyntaxException if there's a syntax error in the JSON
     */
    public JsonConfig(File file, Gson gson) throws IOException {
        this(file);
        this.gson = gson;
    }

    /**
     * Saves changes to the file.
     *
     * @throws IOException if there's an error writing to the file
     */
    public void save() throws IOException {
        if (!folder.exists()) folder.mkdirs();
        if (!file.exists()) file.createNewFile();

        Files.writeString(file.toPath(), gson.toJson(root));
    }

    /**
     * Safely saves changes to the file, wrapping exceptions into runtime.
     *
     * @see #save()
     */
    public void trySave() {
        try {
            save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the JSON element by path.
     *
     * @param path Path using dot and array syntax (e.g., "object.array[5].field")
     * @return Found JSON element
     * @throws JsonSyntaxException for invalid path or type mismatches
     */
    public JsonElement get(String path) {
        return getter.get(path);
    }

    /**
     * Gets a value by path with deserialization via Codec.
     *
     * @param codec Codec for JSON ↔ object conversion
     * @return Deserialized object
     * @see #get(String)
     */
    public <T> T get(String path, Codec<T> codec) {
        return codec.parse(JsonOps.INSTANCE, get(path)).getOrThrow();
    }

    /**
     * Sets a JSON value at the specified path.
     *
     * @param path  Target path using dot and array syntax
     * @param value JSON value to set (primitive, object, or array)
     * @throws JsonSyntaxException for path conflicts or invalid structure
     */
    public void set(String path, JsonElement value) {
        setter.set(path, value);
    }

    /**
     * Sets a value with serialization via Codec.
     *
     * @param codec Codec for object → JSON conversion
     * @see #set(String, JsonElement)
     */
    public <T> void set(String path, Codec<T> codec, T value) {
        set(path, codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow());
    }

    public JsonElement getRoot() {
        return root;
    }

    public File getFile() {
        return file;
    }

    public record Getter(JsonElement root) {
        public JsonElement get(String path) {
            String[] parts = path.split("\\.");
            JsonElement current = root;

            for (String part : parts) {
                if (current == null || current.isJsonNull()) {
                    throw new JsonSyntaxException("Path segment '" + part + "' not found");
                }

                var arrayMatcher = ARRAY_PATTERN.matcher(part);

                if (arrayMatcher.matches())
                    current = parseArray(current, arrayMatcher.group(1), Integer.parseInt(arrayMatcher.group(2)));
                else current = parseObject(current, part);

                if (current == null) throw new JsonSyntaxException("Invalid path segment: " + part);
            }

            return current;
        }

        private JsonElement parseObject(JsonElement current, String key) {
            if (current.isJsonObject()) {
                JsonObject obj = current.getAsJsonObject();
                return obj.has(key) ? obj.get(key) : null;
            }
            return null;
        }

        private JsonElement parseArray(JsonElement current, String name, int index) {
            if (current.isJsonObject()) {
                JsonObject obj = current.getAsJsonObject();
                if (!obj.has(name) || !obj.get(name).isJsonArray()) return null;

                JsonArray array = obj.getAsJsonArray(name);
                if (index < 0 || index >= array.size()) return null;

                return array.get(index);
            }
            return null;
        }
    }

    public record Setter(JsonElement root) {
        public void set(String path, JsonElement value) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Path cannot be null or empty");
            }

            String[] parts = path.split("\\.");
            if (parts.length == 0) return;

            JsonElement parent = root;
            String lastPart = parts[parts.length - 1];

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                parent = traverse(parent, part);
                if (parent == null) {
                    throw new JsonSyntaxException("Invalid path segment: " + part);
                }
            }

            applyValue(parent, lastPart, value);
        }

        private JsonElement traverse(JsonElement current, String part) {
            var arrayMatcher = ARRAY_PATTERN.matcher(part);
            if (arrayMatcher.matches()) {
                String name = arrayMatcher.group(1);
                int index = Integer.parseInt(arrayMatcher.group(2));
                return traverseArray(current, name, index);
            } else {
                return traverseObject(current, part);
            }
        }

        private JsonElement traverseObject(JsonElement current, String key) {
            if (!current.isJsonObject()) {
                throw new JsonSyntaxException("Element at '" + key + "' is not an object");
            }
            JsonObject obj = current.getAsJsonObject();
            if (!obj.has(key)) obj.add(key, new JsonObject());
            return obj.get(key);
        }

        private JsonElement traverseArray(JsonElement current, String name, int index) {
            if (!current.isJsonObject()) {
                throw new JsonSyntaxException("Element at '" + name + "' is not an array");
            }

            JsonObject obj = current.getAsJsonObject();
            if (!obj.has(name)) obj.add(name, new JsonArray());

            JsonArray array = obj.getAsJsonArray(name);
            while (array.size() <= index) {
                array.add(JsonNull.INSTANCE);
            }
            return array.get(index);
        }

        private void applyValue(JsonElement parent, String lastPart, JsonElement value) {
            var arrayMatcher = ARRAY_PATTERN.matcher(lastPart);
            if (arrayMatcher.matches()) {
                String name = arrayMatcher.group(1);
                int index = Integer.parseInt(arrayMatcher.group(2));
                if (!parent.isJsonObject()) {
                    throw new JsonSyntaxException("Parent is not an object for array: " + lastPart);
                }
                JsonObject obj = parent.getAsJsonObject();
                JsonArray array = obj.has(name) ? obj.getAsJsonArray(name) : new JsonArray();
                if (!obj.has(name)) {
                    obj.add(name, array);
                }
                while (array.size() <= index) {
                    array.add(JsonNull.INSTANCE);
                }
                array.set(index, value);
            } else {
                if (!parent.isJsonObject()) {
                    throw new JsonSyntaxException("Parent is not an object: " + lastPart);
                }
                parent.getAsJsonObject().add(lastPart, value);
            }
        }
    }
}

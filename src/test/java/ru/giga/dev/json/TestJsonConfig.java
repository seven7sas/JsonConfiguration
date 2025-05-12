package ru.giga.dev.json;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TestJsonConfig {

    @TempDir
    Path dir;

    @Test
    void test() throws IOException {
        File testFile = dir.resolve("test/data.json").toFile();
        JsonConfig json = new JsonConfig(testFile);

        json.set("murk_nasral", JsonParser.parseString("true"));
        assertTrue(json.get("murk_nasral").getAsBoolean());

        String uuid = UUID.randomUUID().toString();
        json.set("data", Codec.STRING, uuid);
        assertEquals(uuid, json.get("data", Codec.STRING));

        for (int i = 0; i < 10; i++) {
            json.set("array[" + i + "]", Codec.STRING, UUID.randomUUID().toString().substring(0, 6));
        }

        assertNotNull(json.get("array"));
        assertEquals(10, json.get("array").getAsJsonArray().size());
    }
}

```java
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import ru.giga.dev.json.JsonConfig;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class Example {
    public static void main(String[] args) throws IOException {
        JsonConfig json = new JsonConfig(new File("src/main/resources", "data.json"));

        json.set("murk_nasral", JsonParser.parseString("true"));
        json.set("data", Codec.STRING, UUID.randomUUID().toString());
        for (int i = 0; i < 10; i++) {
            json.set("array[" + i + "]", Codec.STRING, UUID.randomUUID().toString().substring(0, 6));
        }
        System.out.println(json.get("data", Codec.STRING));
        System.out.println(json.get("array"));
        json.save();
    }
}
```
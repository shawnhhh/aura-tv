package com.github.catvod.parser;
import java.util.LinkedHashMap;
public final class JsonM467Fixture {
    public static String parse(LinkedHashMap<String,String> parsers, String url) {
        if (!parsers.get("json").contains("cat_ext=")) throw new IllegalArgumentException("missing extUrl");
        return "{\"url\":\"https://fixture.test/media\",\"parse\":1}";
    }
}

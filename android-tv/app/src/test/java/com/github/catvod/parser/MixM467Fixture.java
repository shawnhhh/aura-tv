package com.github.catvod.parser;
import java.util.LinkedHashMap;
import java.util.HashMap;
public final class MixM467Fixture {
    public static String parse(LinkedHashMap<String,HashMap<String,String>> parsers,
        String name, String flag, String url) {
        if (!"mixed".equals(name) || !"vip".equals(flag) ||
            !"1".equals(parsers.get("json").get("type"))) throw new IllegalArgumentException("ABI order");
        return "{\"url\":\"https://fixture.test/stream\",\"jxFrom\":\"fixture\"}";
    }
}

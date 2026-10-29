package com.github.catvod;

/**
 * CatVod's getProxyUrl() bridge.
 *
 * HERRIN only exposes a randomly chosen 127.0.0.1 listener in the isolated
 * :spider process. The session is a per-source, ephemeral capability token.
 * This is intentionally not a LAN HTTP API and never exposes Android secrets.
 */
public class Proxy {
    private static volatile int port = -1;
    private static volatile String session = "";

    public static void set(int value) {
        set(value, "");
    }

    public static void set(int value, String token) {
        if (value < 1 || value > 65535) {
            port = -1;
            session = "";
            return;
        }
        port = value;
        session = token == null ? "" : token;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(boolean local) {
        // HERRIN does not bind the proxy to the TV's LAN interface.
        String url = "http://127.0.0.1:" + port + "/proxy";
        return session.isEmpty() ? url : url + "?session=" + session;
    }
}

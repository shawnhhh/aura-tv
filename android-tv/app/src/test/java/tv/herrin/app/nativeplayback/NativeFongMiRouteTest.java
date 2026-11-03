package tv.herrin.app.nativeplayback;

import java.io.ByteArrayInputStream;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeFongMiRouteTest {
    @Test public void healthProbeChecksActualSessionIncludingStaticJarRoutes() throws Exception {
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", p -> null, p -> null)) {
            assertEquals(NativeFongMiRoute.Health.READY,
                NativeFongMiRoute.probe(proxy.siteUrl(), "approved"));
            assertEquals(NativeFongMiRoute.Health.READY,
                NativeFongMiRoute.probe(proxy.baseUrl() + "&do=parse", "approved"));
            assertEquals(NativeFongMiRoute.Health.HTTP_403,
                NativeFongMiRoute.probe(proxy.siteUrl(), "foreign"));
            assertEquals(NativeFongMiRoute.Health.NOT_PROXY,
                NativeFongMiRoute.probe("https://example.org/video.mp4", "approved"));
        }
    }

    @Test public void originalSpiderProxyAliasPreservesUnknownOperation() throws Exception {
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", p ->
            new Object[]{200, "video/mp4", new ByteArrayInputStream(new byte[]{1})}, p ->
            new Object[]{200, "video/mp4", new ByteArrayInputStream(new byte[]{2})})) {
            String out = NativeFongMiRoute.url("proxy://do=parse&url=https%3A%2F%2Fexample.org%2Fa",
                "approved", proxy);
            assertNotNull(out);
            assertTrue(out.startsWith(proxy.baseUrl()));
            assertTrue(out.contains("&do=parse"));
            assertTrue(out.contains("&url=https%3A%2F%2Fexample.org%2Fa"));
            assertFalse(out.contains("siteKey="));
        }
    }

    @Test public void rebasesApprovedSiteUrlButRejectsForgedSessionsAndSites() throws Exception {
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", p -> null, null)) {
            String original = "http://127.0.0.1:9978/proxy?do=jar&siteKey=approved&play=1";
            String result = NativeFongMiRoute.url(original, "approved", proxy);
            assertNotNull(result);
            assertTrue(result.startsWith(proxy.baseUrl()));
            assertTrue(result.contains("siteKey=approved"));
            assertTrue(result.contains("play=1"));
            assertNull(NativeFongMiRoute.url(
                "proxy://do=jar&siteKey=another", "approved", proxy));
            assertNull(NativeFongMiRoute.url(
                proxy.baseUrl().replace(proxy.sessionToken(), "stale") +
                    "&do=jar&siteKey=approved", "approved", proxy));
            assertNull(NativeFongMiRoute.url(
                "proxy://do=parse", "approved", proxy));
            assertNull(NativeFongMiRoute.url(
                "http://127.0.0.1:9978/private?do=jar&siteKey=approved", "approved", proxy));
            assertEquals("https://example.org/x.mp4",
                NativeFongMiRoute.url("https://example.org/x.mp4", "approved", proxy));
        }
    }

    @Test public void normalizesNestedAndArrayPlayerContentWithoutDroppingMediaOptions() throws Exception {
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", p -> null, p -> null)) {
            String raw = "{\"jx\":0,\"header\":{\"Referer\":\"https://example.org\"},"
                + "\"url\":{\"values\":[{\"n\":\"4K\",\"v\":\"proxy://do=parse&url=x\"},"
                + "{\"n\":\"1080p\",\"v\":\"https://example.org/a.mp4\"}]}}";
            String out = NativeFongMiRoute.playerContent(raw, "approved", proxy);
            assertNotNull(out);
            JSONObject object = new JSONObject(out);
            assertEquals(0, object.getInt("jx"));
            assertEquals("https://example.org", object.getJSONObject("header").getString("Referer"));
            assertTrue(object.getJSONObject("url").getJSONArray("values")
                .getJSONObject(0).getString("v").startsWith(proxy.baseUrl()));
            assertEquals("https://example.org/a.mp4", object.getJSONObject("url")
                .getJSONArray("values").getJSONObject(1).getString("v"));
            assertNull(NativeFongMiRoute.playerContent(
                "{\"url\":\"proxy://do=parse&siteKey=forged\"}", "approved", proxy));
        }
    }
}

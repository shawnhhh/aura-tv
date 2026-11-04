package tv.herrin.app;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.herrin.app.nativeplayback.FongMiPlaybackAdapter;
import tv.herrin.app.nativeplayback.FongMiExoMediaSession;
import tv.herrin.app.nativeplayback.NativeFongMiProxy;

/** Verifies authorized playerContent -> native proxy -> actual Media3 item. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class M479FongMiPlaybackAdapterTest {
    @Test public void resolvedProxyRouteReachesExoWithoutLegacyProxy() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved",
                path -> new Object[]{200, "video/mp4",
                    new java.io.ByteArrayInputStream(new byte[]{1, 2, 3})},
                path -> null)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                String response = "{\"jx\":0,\"url\":\"proxy://do=video&siteKey=approved\","
                    + "\"header\":{\"Referer\":\"https://example.org/\"}}";
                try (FongMiExoMediaSession session = FongMiPlaybackAdapter.prepareResolved(
                        context, "episode/1", response, "approved", proxy)) {
                    assertEquals("episode/1", session.player().getCurrentMediaItem().mediaId);
                    assertTrue(session.player().getCurrentMediaItem().localConfiguration.uri
                            .toString().startsWith(proxy.baseUrl()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test public void selectedQualityVariantReachesSameExoMediaItem() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", path -> null, null)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                String response = "{\"jx\":0,\"url\":{\"position\":1,\"values\":["
                    + "{\"n\":\"4K\",\"v\":\"https://example.org/4k.mp4\"},"
                    + "{\"n\":\"1080p\",\"v\":\"https://example.org/1080.mp4\"}]}}";
                try (FongMiExoMediaSession session = FongMiPlaybackAdapter.prepareResolved(
                        context, "episode/quality", response, "approved", proxy)) {
                    assertEquals("https://example.org/1080.mp4",
                        session.player().getCurrentMediaItem().localConfiguration.uri.toString());
                } catch (Exception e) { throw new AssertionError(e); }
                assertThrows(IllegalArgumentException.class, () ->
                    FongMiPlaybackAdapter.prepareResolved(context, "episode",
                        "{\"url\":{\"position\":9,\"values\":[{\"v\":\"https://example.org/a.mp4\"}]}}",
                        "approved", proxy));
            });
        }
    }

    @Test public void unresolvedParseAndForeignSiteNeverReachPlayer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", path -> null, null)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertThrows(IllegalArgumentException.class, () ->
                    FongMiPlaybackAdapter.prepareResolved(context, "episode",
                        "{\"jx\":1,\"url\":\"https://example.org/watch\"}",
                        "approved", proxy));
                assertThrows(IllegalArgumentException.class, () ->
                    FongMiPlaybackAdapter.prepareResolved(context, "episode",
                        "{\"url\":\"proxy://do=video&siteKey=foreign\"}",
                        "approved", proxy));
            });
        }
    }
}

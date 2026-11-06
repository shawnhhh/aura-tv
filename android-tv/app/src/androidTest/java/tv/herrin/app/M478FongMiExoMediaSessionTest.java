package tv.herrin.app;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.herrin.app.nativeplayback.FongMiExoMediaSession;
import java.util.Map;

/** Media3 host-bridge contract; does not execute a remote Spider or contact external media hosts. */
@RunWith(AndroidJUnit4.class)
public final class M478FongMiExoMediaSessionTest {
    @Test public void createsRealExoPlayerAndPreparesAuthorizedMediaItem() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (FongMiExoMediaSession session = FongMiExoMediaSession.create(
                    context, Map.of("Referer", "https://authorized.example/"))) {
                session.prepare("site/episode/1", "http://127.0.0.1:1/playlist.m3u8",
                        MimeTypes.APPLICATION_M3U8);
                assertNotNull(session.player());
                assertNotNull(session.player().getCurrentMediaItem());
                assertEquals("site/episode/1",
                        session.player().getCurrentMediaItem().mediaId);
                assertEquals(MimeTypes.APPLICATION_M3U8,
                        session.player().getCurrentMediaItem().localConfiguration.mimeType);
            }
        });
    }

    @Test public void rejectsUnsafeUrisAndHeaderInjectionWithoutOpeningNetwork() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThrows(IllegalArgumentException.class, () ->
                    FongMiExoMediaSession.create(context, Map.of("Referer", "ok\r\nInjected: 1")));
            try (FongMiExoMediaSession session = FongMiExoMediaSession.create(
                    context, Map.of())) {
                assertThrows(IllegalArgumentException.class,
                        () -> session.prepare("id", "file:///sdcard/private.mp4", null));
                assertThrows(IllegalArgumentException.class,
                        () -> session.prepare("id", "javascript:alert(1)", null));
                assertThrows(IllegalArgumentException.class,
                        () -> session.prepare("", "http://127.0.0.1:1/video.mp4", null));
            }
        });
    }

    @Test public void remoteCommandsControlSameRealExoInstanceAndRejectInvalidSeek() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (FongMiExoMediaSession session = FongMiExoMediaSession.create(context, Map.of())) {
                session.prepare("episode", "http://127.0.0.1:1/clip.mp4", null);
                session.setVideoSurface(null);
                session.play();
                assertTrue(session.player().getPlayWhenReady());
                session.pause();
                assertFalse(session.player().getPlayWhenReady());
                assertThrows(IllegalArgumentException.class, () -> session.seekTo(-1));
                session.seekTo(0);
            }
        });
    }

    @Test public void closeIsIdempotentAndPreventsReuse() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FongMiExoMediaSession session = FongMiExoMediaSession.create(context, Map.of());
            session.close();
            session.close();
            assertThrows(IllegalStateException.class, session::player);
            assertThrows(IllegalStateException.class, session::play);
            assertThrows(IllegalStateException.class, () -> session.setVideoSurface(null));
        });
    }
}

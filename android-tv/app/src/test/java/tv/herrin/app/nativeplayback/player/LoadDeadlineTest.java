package tv.herrin.app.nativeplayback.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoadDeadlineTest {
    @Test public void currentBufferingAttemptExpiresAtItsDeadline() {
        LoadDeadline deadline = new LoadDeadline();
        long attempt = deadline.begin(100, 15000);
        assertFalse(deadline.isExpired(attempt, 15099));
        assertTrue(deadline.isExpired(attempt, 15100));
    }
    @Test public void replacingSourceInvalidatesOldTimeoutEvenAfterItExpires() {
        LoadDeadline deadline = new LoadDeadline();
        long first = deadline.begin(100, 15000);
        long second = deadline.begin(1100, 15000);
        assertFalse(deadline.isExpired(first, 20000));
        assertTrue(deadline.isExpired(second, 20000));
    }
    @Test public void readyStopOrBackgroundCancellationInvalidatesCallback() {
        LoadDeadline deadline = new LoadDeadline();
        long attempt = deadline.begin(100, 15000);
        deadline.cancel();
        assertFalse(deadline.isExpired(attempt, Long.MAX_VALUE));
        long resumed = deadline.begin(50000, 15000);
        assertFalse(deadline.isExpired(resumed, 50001));
        assertTrue(deadline.isExpired(resumed, 65000));
    }
    @Test public void deadlineSaturatesWithoutExpiringEarly() {
        LoadDeadline deadline = new LoadDeadline();
        long attempt = deadline.begin(Long.MAX_VALUE - 5, 15);
        assertFalse(deadline.isExpired(attempt, Long.MAX_VALUE - 1));
        assertTrue(deadline.isExpired(attempt, Long.MAX_VALUE));
    }
    @Test public void nonPositiveTimeoutIsRejected() {
        LoadDeadline deadline = new LoadDeadline();
        assertThrows(IllegalArgumentException.class, () -> deadline.begin(0, 0));
        assertThrows(IllegalArgumentException.class, () -> deadline.begin(0, -1));
    }
}

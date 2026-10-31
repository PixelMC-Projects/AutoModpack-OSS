package pl.skidam.automodpack_core.oss.client;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ConsentCountdownTest {
    @Test void requiresTenFullVisibleSecondsAndReinitializationCannotBypass() {
        AtomicLong time = new AtomicLong(100);
        ConsentCountdown countdown = new ConsentCountdown(time::get, Duration.ofSeconds(10));
        assertFalse(countdown.canAccept());
        countdown.shown();
        for (int i = 0; i < 100; i++) countdown.shown(); // resize/init do not advance clock
        time.addAndGet(9_999_999_999L);
        assertEquals(1, countdown.remainingSeconds());
        assertFalse(countdown.canAccept());
        time.incrementAndGet();
        assertTrue(countdown.canAccept());
    }
    @Test void timeSpentOnAnotherScreenDoesNotCount() {
        AtomicLong time = new AtomicLong();
        ConsentCountdown countdown = new ConsentCountdown(time::get, Duration.ofSeconds(10));
        countdown.shown(); time.addAndGet(4_000_000_000L); countdown.hidden();
        time.addAndGet(100_000_000_000L);
        assertEquals(6, countdown.remainingSeconds());
        assertFalse(countdown.canAccept());
        countdown.shown(); time.addAndGet(6_000_000_000L);
        assertTrue(countdown.canAccept());
    }
}

package pl.skidam.automodpack_core.oss.client;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Measures visible time, independently of game ticks, resize and screen re-initialization. */
public final class ConsentCountdown {
    private final LongSupplier clock;
    private final long required;
    private long accumulated;
    private long shownAt;
    private boolean shown;

    public ConsentCountdown() { this(System::nanoTime, Duration.ofSeconds(10)); }
    public ConsentCountdown(LongSupplier clock, Duration duration) {
        this.clock = clock;
        this.required = duration.toNanos();
        if (required < 0) throw new IllegalArgumentException("negative duration");
    }
    public synchronized void shown() {
        if (!shown) { shownAt = clock.getAsLong(); shown = true; }
    }
    public synchronized void hidden() {
        if (shown) { accumulated += elapsed(); shown = false; }
    }
    private long elapsed() { return Math.max(0, clock.getAsLong() - shownAt); }
    public synchronized long remainingSeconds() {
        long remaining = Math.max(0, required - accumulated - (shown ? elapsed() : 0));
        return (remaining + 999_999_999L) / 1_000_000_000L;
    }
    public synchronized boolean canAccept() { return shown && remainingSeconds() == 0; }
}

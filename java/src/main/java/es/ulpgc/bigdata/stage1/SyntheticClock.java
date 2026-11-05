package es.ulpgc.bigdata.stage1;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * Benchmark clock: N books per hour starting from a fixed date.
 * With the real clock the whole benchmark would end up in the same hour folder.
 */
public class SyntheticClock implements Supplier<ZonedDateTime> {
    private final ZonedDateTime start;
    private final int booksPerHour;
    private int count;

    public SyntheticClock() {
        this(10, 0);
    }

    public SyntheticClock(int booksPerHour, int offsetHours) {
        this.start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).plusHours(offsetHours);
        this.booksPerHour = booksPerHour;
    }

    @Override
    public ZonedDateTime get() {
        return start.plusHours(count / booksPerHour);
    }

    public void tick() {
        count++;
    }
}

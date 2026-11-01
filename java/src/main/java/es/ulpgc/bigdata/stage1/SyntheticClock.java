package es.ulpgc.bigdata.stage1;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * Reloj del benchmark: N libros por hora a partir de una fecha fija.
 * Con el reloj real todo el benchmark caeria en la misma carpeta horaria.
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

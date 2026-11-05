package es.ulpgc.bigdata.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DatalakeTest {
    @TempDir
    Path tmp;

    @ParameterizedTest
    @CsvSource({
        "book, 1342/header.txt",
        "range, 001000/1342.header.txt",
        "time, 20260101/00/1342.header.txt",
    })
    void paths(String layout, String expected) throws Exception {
        Datalake lake = new Datalake(tmp, layout, new SyntheticClock());
        lake.writeBook(1342, "h", "b");
        Path[] found = lake.locate(1342);
        assertEquals(expected, lake.relative(found[0]));
        assertEquals("b", Files.readString(found[1]));
    }

    @Test
    void rangeBucketHasSixDigits() {
        Datalake lake = new Datalake(tmp, "range");
        assertEquals("000000", lake.partitionFor(5));
        assertEquals("075000", lake.partitionFor(75999));
    }

    @Test
    void unknownLayout() {
        assertThrows(IllegalArgumentException.class, () -> new Datalake(tmp, "weekly"));
    }

    @Test
    void timeLayoutUsesRegistry() throws Exception {
        SyntheticClock clock = new SyntheticClock(2, 0);
        Datalake lake = new Datalake(tmp, "time", clock);
        for (int id : new int[] {1, 2, 3}) {
            lake.writeBook(id, "h", "b");
            clock.tick();
        }
        Datalake fresh = new Datalake(tmp, "time");
        assertEquals(Map.of(1, "20260101/00", 2, "20260101/00", 3, "20260101/01"), fresh.loadRegistry());
        assertNotNull(fresh.locate(3));
        assertNull(fresh.locate(4));
    }

    @Test
    void scanAndPrunedScan() throws Exception {
        SyntheticClock clock = new SyntheticClock(1, 0);
        Datalake lake = new Datalake(tmp, "time", clock);
        for (int id = 1; id <= 5; id++) {
            lake.writeBook(id, "h", "b");
            clock.tick();
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), lake.scan().ids());
        assertEquals(Set.of(4, 5), lake.scan("20260101/03").ids());
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "book", "range"})
    void crashBetweenRenamesAndReconcile(String layout) throws Exception {
        Datalake lake = new Datalake(tmp.resolve("lake"), layout, new SyntheticClock());
        Control control = new Control(tmp.resolve("control"));
        lake.writeBook(1, "h", "b");
        control.markDownloaded(1);
        assertThrows(SimulatedCrashException.class, () -> lake.writeBook(2, "h", "b", true));

        ScanResult scan = lake.scan();
        assertEquals(Set.of(1), scan.ids());
        assertEquals(1, scan.orphans.size());
        assertEquals(1, scan.temps.size());

        Map<String, Integer> stats = lake.reconcile(control);
        assertEquals(1, stats.get("orphans"));
        assertEquals(1, stats.get("temps"));
        scan = lake.scan();
        assertEquals(Set.of(1), scan.ids());
        assertTrue(scan.orphans.isEmpty() && scan.temps.isEmpty());
    }

    @Test
    void reconcileAddsCompleteBooksToControl() throws Exception {
        Datalake lake = new Datalake(tmp.resolve("lake"), "range");
        Control control = new Control(tmp.resolve("control"));
        lake.writeBook(7, "h", "b");          // crash before writing it to the control file
        control.markDownloaded(99);           // written down but with no files
        Map<String, Integer> stats = lake.reconcile(control);
        assertEquals(Set.of(7), control.downloaded());
        assertEquals(1, stats.get("added_to_control"));
        assertEquals(1, stats.get("removed_from_control"));
        assertEquals(List.of("temps", "orphans", "duplicates", "added_to_control", "removed_from_control"),
                List.copyOf(stats.keySet()));
    }

    @Test
    void reconcileRemovesTimeDuplicates() throws Exception {
        Datalake lake = new Datalake(tmp.resolve("lake"), "time", new SyntheticClock());
        lake.writeBook(1, "h", "b");
        lake.clock = new SyntheticClock(10, 24);
        lake.writeBook(1, "h", "b");
        assertEquals(List.of("20260101/00", "20260102/00"), lake.scan().complete.get(1));
        Map<String, Integer> stats = lake.reconcile(new Control(tmp.resolve("control")));
        assertEquals(1, stats.get("duplicates"));
        assertEquals(List.of("20260102/00"), lake.scan().complete.get(1));
        assertEquals("1\t20260102/00\n", Files.readString(tmp.resolve("lake").resolve(Datalake.REGISTRY)));
    }

    @Test
    void controlIgnoresCutLastLine() throws Exception {
        Control control = new Control(tmp);
        control.markDownloaded(10);
        control.markDownloaded(11);
        Files.write(control.downloadedFile, "12".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertEquals(Set.of(10, 11), control.downloaded());
    }

    @Test
    void controlPendingAndFailed() throws Exception {
        Control control = new Control(tmp);
        for (int id : new int[] {1, 2, 3}) {
            control.markDownloaded(id);
        }
        control.markIndexed(2);
        control.markFailed(4, "not_found");
        assertEquals(Set.of(1, 3), control.pending());
        assertEquals(Map.of(4, "not_found"), control.failed());
    }

    @Test
    void storageStats() throws Exception {
        Datalake lake = new Datalake(tmp, "book");
        lake.writeBook(1, "hh", "bbb");
        lake.writeBook(2, "hh", "bbb");
        Map<String, Long> stats = lake.storageStats();
        assertEquals(4, stats.get("files"));
        assertEquals(2, stats.get("dirs"));
        assertEquals(10, stats.get("bytes"));
        assertEquals(0, stats.get("aux_bytes"));
    }

    @Test
    void filesAreUtf8WithoutTrailingNewline() throws Exception {
        Datalake lake = new Datalake(tmp, "range");
        lake.writeBook(3, "T\u00EDtulo", "body \uD835\uDD04");
        Path[] found = lake.locate(3);
        assertEquals("T\u00EDtulo", new String(Files.readAllBytes(found[0]), StandardCharsets.UTF_8));
        assertEquals(List.of("T\u00EDtulo", "body \uD835\uDD04"), List.of(lake.readBook(3)));
    }

    @Test
    void reconcileTimeLayoutOnEmptyFolder() throws Exception {
        Datalake lake = new Datalake(tmp.resolve("lake"), "time");
        assertEquals(0, lake.reconcile(new Control(tmp.resolve("control"))).get("temps"));
    }
}

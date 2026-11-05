package es.ulpgc.bigdata.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineTest {
    @TempDir
    Path tmp;

    static void makeCache(Path folder) throws Exception {
        Files.createDirectories(folder);
        String[][] books = {
            {"1", "Moby Dick", "Herman Melville", "the whale and the sea"},
            {"2", "Treasure Island", "R. L. Stevenson", "an island with gold treasure by the sea"},
        };
        // book 3 doesn't exist (404)
        for (String[] b : books) {
            String text = "Title: " + b[1] + "\n\nAuthor: " + b[2] + "\n\nLanguage: English\n"
                    + "*** START OF THE PROJECT GUTENBERG EBOOK X ***\n" + b[3]
                    + "\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\n";
            Files.writeString(folder.resolve("pg" + b[0] + ".txt"), text, StandardCharsets.UTF_8);
        }
        Files.writeString(folder.resolve("pg4.txt"), "no markers here", StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> results(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("results");
    }

    @Test
    void controlLoopDownloadsThenIndexes() throws Exception {
        makeCache(tmp.resolve("cache"));
        List<String> logs = new ArrayList<>();
        Path data = tmp.resolve("data");
        try (Pipeline pipe = new Pipeline(data, "range", "sqlite", "cache:" + tmp.resolve("cache"),
                List.of(1, 2, 3, 4), 0, logs::add, null, null)) {
            assertEquals(new Pipeline.Step("downloaded", 1), pipe.step());
            assertEquals(new Pipeline.Step("indexed", 1), pipe.step());      // pending books are indexed first
            assertEquals(new Pipeline.Step("downloaded", 2), pipe.step());
            assertEquals(new Pipeline.Step("indexed", 2), pipe.step());
            assertEquals(new Pipeline.Step("failed", 3), pipe.step());
            assertEquals(new Pipeline.Step("failed", 4), pipe.step());
            assertEquals(new Pipeline.Step("idle", null), pipe.step());
            assertEquals(Map.of(3, "not_found", 4, "no_markers"), pipe.control.failed());
            assertEquals(Set.of(1, 2), pipe.control.downloaded());
            assertEquals(Set.of(1, 2), pipe.control.indexed());
        }
        assertEquals("[CONTROL] downloading book 1", logs.get(0));
        assertEquals("[CONTROL] book 3 skipped: not_found", logs.get(5));

        Map<String, Object> result = Pipeline.search(data, "sqlite", "the sea", null, null);
        assertEquals(List.of("sea"), result.get("terms"));
        assertEquals(1, results(result).get(0).get("book_id"));
        assertEquals(2, results(result).get(1).get("book_id"));
        assertEquals("Treasure Island", results(result).get(1).get("title"));
        assertEquals(1, Pipeline.search(data, "sqlite", "gold island", null, null).get("total"));
        assertEquals(0, Pipeline.search(data, "sqlite", "the of", null, null).get("total"));
    }

    @Test
    void pipelineResumesWithoutDuplicates() throws Exception {
        makeCache(tmp.resolve("cache"));
        String source = "cache:" + tmp.resolve("cache");
        Path data = tmp.resolve("data");
        try (Pipeline pipe = new Pipeline(data, "range", "json", source, List.of(1, 2), 0, m -> { }, null, null)) {
            pipe.run(3);
        }
        try (Pipeline pipe = new Pipeline(data, "range", "json", source, List.of(1, 2), 0, m -> { }, null, null)) {
            pipe.run(10);
        }
        String text = Files.readString(data.resolve("control").resolve("downloaded_books.txt"));
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\s+")));
        lines.sort(null);
        assertEquals(List.of("1", "2"), lines);
        assertEquals(2, Pipeline.search(data, "json", "sea", null, null).get("total"));
    }

    @Test
    void reconcileIsLoggedLikePython() throws Exception {
        makeCache(tmp.resolve("cache"));
        Path data = tmp.resolve("data");
        Files.createDirectories(data.resolve("control"));
        Files.writeString(data.resolve("control").resolve("downloaded_books.txt"), "9\n");
        List<String> logs = new ArrayList<>();
        try (Pipeline pipe = new Pipeline(data, "book", "hier", "cache:" + tmp.resolve("cache"), List.of(1), 0,
                logs::add, null, null)) {
            pipe.run(5);
        }
        assertEquals(List.of(
                "[CONTROL] reconciliation: {'temps': 0, 'orphans': 0, 'duplicates': 0, 'added_to_control': 0, 'removed_from_control': 1}",
                "[CONTROL] downloading book 1",
                "[CONTROL] indexing book 1",
                "[CONTROL] nothing left to do"), logs);
    }
}

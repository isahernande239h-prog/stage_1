package es.ulpgc.bigdata.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchTest {
    @Test
    void percentileLikePython() {
        double[] values = {5, 1, 4, 2, 3};
        assertEquals(3.0, Bench.percentile(values, 0.5));
        assertEquals(4.8, Bench.percentile(values, 0.95), 1e-12);
        assertEquals(7.0, Bench.percentile(new double[] {7}, 0.95));
    }

    @Test
    void jsonLikePython() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("book_id", 1);
        row.put("title", "Caf\u00E9 \"x\"\t");
        row.put("year", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("terms", List.of("sea"));
        result.put("results", List.of(row));
        result.put("empty", List.of());
        // output of json.dump(result, indent=2, ensure_ascii=False)
        assertEquals("{\n  \"terms\": [\n    \"sea\"\n  ],\n  \"results\": [\n    {\n      \"book_id\": 1,\n"
                + "      \"title\": \"Caf\u00E9 \\\"x\\\"\\t\",\n      \"year\": null\n    }\n  ],\n  \"empty\": []\n}",
                Json.pretty(result));
        // output of json.dump(result) on one line
        assertEquals("{\"terms\": [\"sea\"], \"results\": [{\"book_id\": 1, \"title\": \"Caf\\u00e9 \\\"x\\\"\\t\", "
                + "\"year\": null}], \"empty\": []}", Json.line(result));
    }

    @Test
    void argsLikeArgparse() {
        Args args = new Args("ingest", new String[] {"--data", "d", "--n=5", "--reconcile", "--clock-offset-hours", "-3"});
        assertEquals("d", args.required("--data"));
        assertEquals(5, args.integer("--n", 1, false));
        assertEquals(-3, args.integer("--clock-offset-hours", 0, false));
        assertEquals(true, args.flag("--reconcile"));
        assertEquals("range", args.choice("--layout", "range", Datalake.LAYOUTS, false));
        args.finish(false);

        Args bad = new Args("ingest", new String[] {"--layout", "weekly", "--other", "x"});
        assertThrows(Args.UsageException.class, () -> bad.choice("--layout", "range", Datalake.LAYOUTS, false));
        assertThrows(Args.UsageException.class, () -> bad.finish(false));
        assertThrows(Args.UsageException.class, () -> new Args("ingest", new String[] {"--data"}));
    }
}

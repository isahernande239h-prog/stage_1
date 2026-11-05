package es.ulpgc.bigdata.stage1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Benchmark commands. Each one runs one configuration and writes a JSON with the measurements.
 * The orchestrator launches them in a new process for the three languages and measures memory from outside.
 */
public final class Bench {
    static final int CRASH_EXIT = 3;

    private Bench() {
    }

    static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    static void emit(Map<String, ?> result) {
        System.out.println(Json.line(result));
    }

    static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    static List<Integer> slice(List<Integer> list, int from, int to) {
        from = Math.min(Math.max(from, 0), list.size());
        to = Math.min(Math.max(to, from), list.size());
        return new ArrayList<>(list.subList(from, to));
    }

    // ---------------------------------------------------------------- datalake

    static Supplier<ZonedDateTime> makeClock(String clock, int offsetHours) {
        if (clock.equals("synthetic")) {
            return new SyntheticClock(10, offsetHours);
        }
        return Datalake::utcNow;
    }

    /** Downloads, splits and saves the ids that are not in the control files yet. Returns {ingested, failed, bytes}. */
    static long[] ingestBooks(Datalake lake, Control control, Source source, List<Integer> ids,
                              Supplier<ZonedDateTime> clock, Integer crashAfter, String crashAt, double delay)
            throws Exception {
        Set<Integer> done = control.downloaded();
        done.addAll(control.failed().keySet());
        long ingested = 0;
        long failed = 0;
        long written = 0;
        for (int bookId : ids) {
            if (done.contains(bookId)) {
                continue;
            }
            if (delay > 0) {
                Thread.sleep(Math.round(delay * 1000));
            }
            String[] parts;
            try {
                parts = Splitter.split(source.fetch(bookId));
            } catch (BookNotFoundException e) {
                control.markFailed(bookId, "not_found");
                failed++;
                continue;
            } catch (NoMarkersException e) {
                control.markFailed(bookId, "no_markers");
                failed++;
                continue;
            }
            boolean crashNow = crashAfter != null && ingested == crashAfter;
            lake.writeBook(bookId, parts[0], parts[1], crashNow && "between_renames".equals(crashAt));
            if (crashNow && "before_control".equals(crashAt)) {
                throw new SimulatedCrashException("before control");
            }
            control.markDownloaded(bookId);
            if (clock instanceof SyntheticClock) {
                ((SyntheticClock) clock).tick();
            }
            ingested++;
            written += parts[0].getBytes(StandardCharsets.UTF_8).length + parts[1].getBytes(StandardCharsets.UTF_8).length;
        }
        return new long[] {ingested, failed, written};
    }

    static int cmdIngest(Args args) throws Exception {
        Path data = Path.of(args.required("--data"));
        String layout = args.choice("--layout", "range", Datalake.LAYOUTS, false);
        String sourceSpec = args.required("--source");
        Path idsFile = Path.of(args.required("--ids"));
        int n = args.integer("--n", 1_000_000_000, false);
        String clockName = args.choice("--clock", "synthetic", List.of("real", "synthetic"), false);
        int offset = args.integer("--clock-offset-hours", 0, false);
        Integer crashAfter = args.integer("--crash-after", null, false);
        String crashAt = args.choice("--crash-at", null, List.of("between_renames", "before_control"), false);
        boolean reconcile = args.flag("--reconcile");
        double delay = args.number("--delay", 0.0);
        args.finish(false);

        Supplier<ZonedDateTime> clock = makeClock(clockName, offset);
        Datalake lake = new Datalake(data.resolve("datalake"), layout, clock);
        Control control = new Control(data.resolve("control"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reconcile_ms", null);
        if (reconcile) {
            long t = System.nanoTime();
            result.put("reconcile", lake.reconcile(control));
            result.put("reconcile_ms", seconds(t) * 1000);
        }

        List<Integer> ids = slice(Spec.loadIds(idsFile), 0, n);
        long t = System.nanoTime();
        long[] counts;
        try {
            counts = ingestBooks(lake, control, Source.open(sourceSpec), ids, clock, crashAfter, crashAt, delay);
        } catch (SimulatedCrashException e) {
            emit(Map.of("crashed", e.getMessage()));
            return CRASH_EXIT;
        }
        double secs = seconds(t);
        result.put("ingested", counts[0]);
        result.put("failed", counts[1]);
        result.put("seconds", secs);
        result.put("bytes", counts[2]);
        result.put("books_per_s", secs > 0 ? counts[0] / secs : 0);
        result.put("mb_per_s", secs > 0 ? counts[2] / 1e6 / secs : 0);
        emit(result);
        return 0;
    }

    static int cmdBenchDatalake(Args args) throws Exception {
        Path data = Path.of(args.required("--data"));
        String layout = args.choice("--layout", null, Datalake.LAYOUTS, true);
        String sourceSpec = args.required("--source");
        Path idsFile = Path.of(args.required("--ids"));
        int n = args.integer("--n", null, true);
        int newBooks = args.integer("--new", 10, false);
        String clockName = args.choice("--clock", "synthetic", List.of("real", "synthetic"), false);
        int offset = args.integer("--clock-offset-hours", 0, false);
        args.finish(false);

        Supplier<ZonedDateTime> clock = makeClock(clockName, offset);
        Datalake lake = new Datalake(data.resolve("datalake"), layout, clock);
        Control control = new Control(data.resolve("control"));
        List<Integer> ids = slice(Spec.loadIds(idsFile), 0, n);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("layout", layout);
        r.put("n", ids.size());

        // 1) write
        long t = System.nanoTime();
        long[] counts = ingestBooks(lake, control, Source.open(sourceSpec), ids, clock, null, null, 0);
        double secs = seconds(t);
        r.put("ingested", counts[0]);
        r.put("write_s", secs);
        r.put("books_per_s", counts[0] / secs);
        r.put("mb_per_s", counts[2] / 1e6 / secs);

        // 2) find a book with the computed path (or the registry for time)
        lake = new Datalake(data.resolve("datalake"), layout);
        t = System.nanoTime();
        if (layout.equals("time")) {
            lake.loadRegistry();
        }
        r.put("registry_load_ms", seconds(t) * 1000);
        t = System.nanoTime();
        for (int bookId : ids) {
            if (lake.locate(bookId) == null) {
                throw new AssertionError("book " + bookId + " not found");
            }
        }
        r.put("lookup_direct_us", seconds(t) / ids.size() * 1e6);

        // 3) find it by walking the tree (no helper structures)
        List<Integer> sample = new ArrayList<>();
        int stepSize = Math.max(1, ids.size() / 10);
        for (int i = 0; i < ids.size() && sample.size() < 10; i += stepSize) {
            sample.add(ids.get(i));
        }
        t = System.nanoTime();
        for (int bookId : sample) {
            if (!lake.scan().complete.containsKey(bookId)) {
                throw new AssertionError("book " + bookId + " not found when walking the tree");
            }
        }
        r.put("lookup_scan_ms", seconds(t) / sample.size() * 1000);

        // 4) detect the new books (the last newBooks are not indexed)
        int cut = newBooks == 0 ? 0 : Math.max(0, ids.size() - newBooks);   // like ids[:-new] and ids[-new:]
        List<Integer> indexed = slice(ids, 0, cut);
        Set<Integer> expected = new HashSet<>(slice(ids, cut, ids.size()));
        StringBuilder sb = new StringBuilder();
        for (int id : indexed) {
            sb.append(id).append('\n');
        }
        Files.writeString(control.indexedFile, sb, StandardCharsets.UTF_8);

        t = System.nanoTime();
        Set<Integer> found = new Control(data.resolve("control")).pending();
        r.put("detect_control_ms", seconds(t) * 1000);
        boolean ok = found.equals(expected);

        t = System.nanoTime();
        found = lake.scan().ids();
        found.removeAll(new Control(data.resolve("control")).indexed());
        r.put("detect_scan_ms", seconds(t) * 1000);
        ok = ok && found.equals(expected);

        if (layout.equals("time")) {
            Map<Integer, String> registry = lake.loadRegistry();
            String last = null;
            for (int id : indexed) {
                String partition = registry.get(id);
                if (last == null || partition.compareTo(last) > 0) {
                    last = partition;
                }
            }
            t = System.nanoTime();
            found = lake.scan(last).ids();
            found.removeAll(new Control(data.resolve("control")).indexed());
            r.put("detect_pruned_ms", seconds(t) * 1000);
            ok = ok && found.equals(expected);
        }
        r.put("detect_ok", ok);

        // 5) disk space
        for (Map.Entry<String, Long> e : lake.storageStats().entrySet()) {
            r.put("storage_" + e.getKey(), e.getValue());
        }
        long controlBytes = 0;
        for (File f : data.resolve("control").toFile().listFiles()) {
            controlBytes += f.length();
        }
        r.put("control_bytes", controlBytes);
        emit(r);
        return 0;
    }

    // ---------------------------------------------------------------- index

    /** Full index in memory and total number of tokens. */
    static class BuiltIndex {
        final Map<String, Map<Integer, Integer>> index = new LinkedHashMap<>();
        long tokens;
    }

    static BuiltIndex buildIndex(Datalake lake, List<Integer> ids) throws Exception {
        BuiltIndex built = new BuiltIndex();
        for (int bookId : ids) {
            String body = lake.readBook(bookId)[1];
            for (Map.Entry<String, Integer> e : Tokenizer.termFrequencies(body).entrySet()) {
                built.index.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).put(bookId, e.getValue());
                built.tokens += e.getValue();
            }
        }
        return built;
    }

    /** Same as percentile() in Python: linear interpolation between the two closest values. */
    static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double k = (sorted.length - 1) * p;
        int lo = (int) k;
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (k - lo);
    }

    static int cmdBenchIndex(Args args) throws Exception {
        Path data = Path.of(args.required("--data"));
        Path datalake = Path.of(args.required("--datalake"));
        Path idsFile = Path.of(args.required("--ids"));
        int n = args.integer("--n", null, true);
        int add = args.integer("--add", 10, false);
        String queriesFile = args.get("--queries", null);
        int reps = args.integer("--query-reps", 5, false);
        String storeName = args.choice("--store", "sqlite", IndexStore.STORES, false);
        String mongoUri = args.get("--mongo-uri", Main.DEFAULT_MONGO_URI);
        String mongoDb = args.get("--mongo-db", Main.DEFAULT_MONGO_DB);
        args.finish(false);

        Path datamarts = data.resolve("datamarts");
        Datalake lake = new Datalake(datalake, "range");
        List<Integer> allIds = Spec.loadIds(idsFile);
        List<Integer> ids = slice(allIds, 0, n);
        List<Integer> extra = slice(allIds, n, n + add);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("store", storeName);
        r.put("n", ids.size());

        IndexStore store = IndexStore.openStore(storeName, datamarts, mongoUri, mongoDb);
        long t0 = System.nanoTime();
        BuiltIndex built = buildIndex(lake, ids);
        long t1 = System.nanoTime();
        store.writeFull(built.index);
        long t2 = System.nanoTime();
        long postings = 0;
        for (Map<Integer, Integer> p : built.index.values()) {
            postings += p.size();
        }
        r.put("tokens", built.tokens);
        r.put("terms", built.index.size());
        r.put("postings", postings);
        r.put("tokenize_s", (t1 - t0) / 1e9);
        r.put("persist_s", (t2 - t1) / 1e9);
        r.put("build_s", (t2 - t0) / 1e9);
        built = null;   // freed before measuring the rest
        r.put("disk_bytes", store.diskUsage());
        store.close();

        store = IndexStore.openStore(storeName, datamarts, mongoUri, mongoDb);
        long t = System.nanoTime();
        store.open();
        r.put("open_ms", seconds(t) * 1000);

        List<String> queries = Spec.loadQueries(queriesFile != null ? Path.of(queriesFile) : null);
        List<List<String>> queryTerms = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String q : queries) {   // the first pass works as warm-up
            List<String> terms = Tokenizer.tokenize(q);
            queryTerms.add(terms);
            List<String> found = new ArrayList<>();
            for (int id : IndexStore.searchIds(store, terms)) {
                found.add(String.valueOf(id));
            }
            lines.add(q + "\t" + String.join(",", found));
        }
        r.put("queries_digest", sha256(String.join("\n", lines)));
        double[] times = new double[reps * queryTerms.size()];
        int k = 0;
        for (int rep = 0; rep < reps; rep++) {
            for (List<String> terms : queryTerms) {
                t = System.nanoTime();
                IndexStore.searchIds(store, terms);
                times[k++] = seconds(t) * 1000;
            }
        }
        double sum = 0;
        for (double x : times) {
            sum += x;
        }
        r.put("query_mean_ms", sum / times.length);
        r.put("query_p50_ms", percentile(times, 0.5));
        r.put("query_p95_ms", percentile(times, 0.95));

        long addTerms = 0;
        long addNanos = 0;
        for (int bookId : extra) {
            Map<String, Integer> tfs = Tokenizer.termFrequencies(lake.readBook(bookId)[1]);
            addTerms += tfs.size();
            t = System.nanoTime();
            store.addBook(bookId, tfs);
            addNanos += System.nanoTime() - t;
        }
        r.put("add_books", extra.size());
        r.put("add_terms", addTerms);
        r.put("add_ms", addNanos / 1e6);
        store.close();
        emit(r);
        return 0;
    }

    /** Builds metadata and index and exports them in a fixed format to compare languages. */
    static int cmdCanonical(Args args) throws Exception {
        Path data = Path.of(args.required("--data"));
        Path datalake = Path.of(args.required("--datalake"));
        Path idsFile = Path.of(args.required("--ids"));
        int n = args.integer("--n", 55, false);
        int add = args.integer("--add", 5, false);
        String out = args.required("--out");
        String storeName = args.choice("--store", "sqlite", IndexStore.STORES, false);
        String mongoUri = args.get("--mongo-uri", Main.DEFAULT_MONGO_URI);
        String mongoDb = args.get("--mongo-db", Main.DEFAULT_MONGO_DB);
        args.finish(false);

        Path datamarts = data.resolve("datamarts");
        Files.createDirectories(datamarts);
        Datalake lake = new Datalake(datalake, "range");
        List<Integer> allIds = Spec.loadIds(idsFile);
        List<Integer> ids = slice(allIds, 0, n);
        List<Integer> extra = slice(allIds, n, n + add);

        MetadataStore metadata = new MetadataStore(datamarts.resolve("metadata.db"));
        List<Integer> all = new ArrayList<>(ids);
        all.addAll(extra);
        for (int bookId : all) {
            Path[] paths = lake.locate(bookId);
            BookMeta meta = HeaderParser.parseHeader(FileUtil.readText(paths[0]));
            metadata.upsert(bookId, meta, lake.relative(paths[0]), lake.relative(paths[1]));
        }

        IndexStore store = IndexStore.openStore(storeName, datamarts, mongoUri, mongoDb);
        store.writeFull(buildIndex(lake, ids).index);
        store.open();
        for (int bookId : extra) {
            store.addBook(bookId, Tokenizer.termFrequencies(lake.readBook(bookId)[1]));
        }

        List<String> metaLines = new ArrayList<>();
        for (Map<String, Object> row : metadata.all()) {
            List<String> values = new ArrayList<>();
            for (String column : new String[] {"book_id", "title", "author", "language", "year", "release_year"}) {
                Object v = row.get(column);
                values.add(v == null ? "" : v.toString());
            }
            metaLines.add(String.join("\t", values));
        }
        List<String> postLines = new ArrayList<>();
        store.terms((term, found) -> {
            List<Integer> bookIds = new ArrayList<>(found.keySet());
            Collections.sort(bookIds);
            StringBuilder line = new StringBuilder(term).append('\t');
            for (int i = 0; i < bookIds.size(); i++) {
                if (i > 0) {
                    line.append(',');
                }
                line.append(bookIds.get(i)).append(':').append(found.get(bookIds.get(i)));
            }
            postLines.add(line.toString());
        });
        store.close();
        metadata.close();
        // Python's sorted() orders by code points, not by UTF-16
        metaLines.sort(IndexStore::compareCodePoints);
        postLines.sort(IndexStore::compareCodePoints);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("store", storeName);
        writeLines(out + ".metadata.tsv", metaLines, "metadata", result);
        writeLines(out + ".postings.tsv", postLines, "postings", result);
        emit(result);
        return 0;
    }

    private static void writeLines(String path, List<String> lines, String kind, Map<String, Object> result) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        String text = sb.toString();
        Files.writeString(Path.of(path), text, StandardCharsets.UTF_8);
        result.put(kind + "_lines", lines.size());
        result.put(kind + "_sha256", sha256(text));
    }
}

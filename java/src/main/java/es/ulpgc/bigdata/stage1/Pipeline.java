package es.ulpgc.bigdata.stage1;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Capa de control: decide en cada paso si indexar un libro pendiente o descargar uno nuevo. */
public class Pipeline implements AutoCloseable {
    /** Resultado de un paso: downloaded, indexed, failed o idle (con bookId null). */
    public record Step(String action, Integer bookId) {
    }

    final Datalake lake;
    final Control control;
    final Source source;
    final IndexStore store;
    final MetadataStore metadata;
    final List<Integer> candidates;
    final double delay;
    final Consumer<String> log;

    public Pipeline(Path dataDir, String layout, String storeName, String source, List<Integer> candidates,
                    double delay, Consumer<String> log, String mongoUri, String mongoDb) throws Exception {
        this.lake = new Datalake(dataDir.resolve("datalake"), layout);
        this.control = new Control(dataDir.resolve("control"));
        this.source = Source.open(source);
        this.store = IndexStore.openStore(storeName, dataDir.resolve("datamarts"), mongoUri, mongoDb);
        this.store.open();
        this.metadata = new MetadataStore(dataDir.resolve("datamarts").resolve("metadata.db"));
        this.candidates = candidates != null ? candidates : Spec.loadIds(null);
        this.delay = delay;
        this.log = log;
    }

    public Map<String, Integer> reconcile() throws Exception {
        Map<String, Integer> stats = lake.reconcile(control);
        boolean changed = false;
        for (int value : stats.values()) {
            changed |= value != 0;
        }
        if (changed) {
            log.accept("[CONTROL] reconciliacion: " + pythonDict(stats));
        }
        return stats;
    }

    public Step step() throws Exception {
        Set<Integer> pending = control.pending();
        if (!pending.isEmpty()) {
            int bookId = Collections.min(pending);
            log.accept("[CONTROL] indexando libro " + bookId);
            indexBook(bookId);
            return new Step("indexed", bookId);
        }

        Set<Integer> done = control.downloaded();
        done.addAll(control.failed().keySet());
        for (int bookId : candidates) {
            if (!done.contains(bookId)) {
                log.accept("[CONTROL] descargando libro " + bookId);
                boolean ok = downloadBook(bookId);
                return new Step(ok ? "downloaded" : "failed", bookId);
            }
        }
        return new Step("idle", null);
    }

    public void run(int steps) throws Exception {
        reconcile();
        for (int i = 0; i < steps; i++) {
            if (step().action().equals("idle")) {
                log.accept("[CONTROL] no queda nada por hacer");
                break;
            }
        }
    }

    boolean downloadBook(int bookId) throws Exception {
        if (delay > 0) {
            Thread.sleep(Math.round(delay * 1000));  // cortesia con gutenberg.org
        }
        String[] parts;
        try {
            parts = Splitter.split(source.fetch(bookId));
        } catch (BookNotFoundException | NoMarkersException | DownloadException e) {
            String reason = "download_error";
            if (e instanceof BookNotFoundException) {
                reason = "not_found";
            } else if (e instanceof NoMarkersException) {
                reason = "no_markers";
            }
            control.markFailed(bookId, reason);
            log.accept("[CONTROL] libro " + bookId + " descartado: " + reason);
            return false;
        }
        lake.writeBook(bookId, parts[0], parts[1]);
        control.markDownloaded(bookId);  // solo cuando los dos ficheros ya estan en su sitio
        return true;
    }

    void indexBook(int bookId) throws Exception {
        Path[] paths = lake.locate(bookId);
        String header = FileUtil.readText(paths[0]);
        String body = FileUtil.readText(paths[1]);
        metadata.upsert(bookId, HeaderParser.parseHeader(header), lake.relative(paths[0]), lake.relative(paths[1]));
        store.addBook(bookId, Tokenizer.termFrequencies(body));
        control.markIndexed(bookId);
    }

    @Override
    public void close() throws Exception {
        store.close();
        metadata.close();
    }

    /** Mismo formato que un dict de Python en el log: {'temps': 0, 'orphans': 1}. */
    static String pythonDict(Map<String, Integer> map) {
        List<String> items = new ArrayList<>();
        map.forEach((k, v) -> items.add("'" + k + "': " + v));
        return "{" + String.join(", ", items) + "}";
    }

    public static Map<String, Object> search(Path dataDir, String storeName, String query, String mongoUri,
                                             String mongoDb) throws Exception {
        IndexStore store = IndexStore.openStore(storeName, dataDir.resolve("datamarts"), mongoUri, mongoDb);
        store.open();
        MetadataStore metadata = new MetadataStore(dataDir.resolve("datamarts").resolve("metadata.db"));
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(Tokenizer.tokenize(query)));
        List<Integer> ids = terms.isEmpty() ? List.of() : IndexStore.searchIds(store, terms);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int bookId : ids) {
            Map<String, Object> meta = metadata.get(bookId);
            if (meta == null) {
                meta = Map.of("book_id", bookId);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : new String[] {"book_id", "title", "author", "language", "year"}) {
                row.put(key, meta.get(key));
            }
            results.add(row);
        }
        store.close();
        metadata.close();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("terms", terms);
        result.put("total", results.size());
        result.put("results", results);
        return result;
    }
}

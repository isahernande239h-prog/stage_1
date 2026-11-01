package es.ulpgc.bigdata.stage1;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Estructuras del indice invertido. Todas tienen la misma interfaz:
 * writeFull guarda un indice completo {termino: {book_id: tf}}, open deja la estructura lista
 * para consultar, addBook anade (o sobrescribe) un libro, postings devuelve {book_id: tf},
 * terms recorre todo para exportar y diskUsage da los bytes en disco.
 */
public interface IndexStore extends AutoCloseable {
    List<String> STORES = List.of("json", "hier", "mongo", "sqlite");

    void writeFull(Map<String, Map<Integer, Integer>> index) throws Exception;

    void open() throws Exception;

    void addBook(int bookId, Map<String, Integer> tfs) throws Exception;

    Map<Integer, Integer> postings(String term) throws Exception;

    void terms(BiConsumer<String, Map<Integer, Integer>> action) throws Exception;

    long diskUsage() throws Exception;

    @Override
    void close() throws Exception;

    static IndexStore openStore(String name, Path datamarts, String mongoUri, String mongoDb) throws Exception {
        Files.createDirectories(datamarts);
        switch (name) {
            case "json":
                return new JsonStore(datamarts);
            case "hier":
                return new HierStore(datamarts);
            case "mongo":
                return new MongoStore(mongoUri, mongoDb);
            case "sqlite":
                return new SqliteStore(datamarts);
            default:
                throw new IllegalArgumentException("estructura desconocida: " + name);
        }
    }

    /** Libros que tienen todos los terminos, ordenados por id. */
    static List<Integer> searchIds(IndexStore store, List<String> terms) throws Exception {
        Set<Integer> result = null;
        for (String term : new LinkedHashSet<>(terms)) {
            Set<Integer> found = store.postings(term).keySet();
            if (result == null) {
                result = new HashSet<>(found);
            } else {
                result.retainAll(found);
            }
            if (result.isEmpty()) {
                return new ArrayList<>();
            }
        }
        if (result == null) {
            return new ArrayList<>();
        }
        List<Integer> ids = new ArrayList<>(result);
        Collections.sort(ids);
        return ids;
    }

    /** Orden por code points, como sorted() en Python o BINARY en SQLite (compareTo usa UTF-16). */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}

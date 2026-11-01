package es.ulpgc.bigdata.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndexStoresTest {
    static final String GREEK = "\u03BF\u03B4\u03BF\u03C2";
    static Boolean mongoUp;

    @TempDir
    Path tmp;
    IndexStore store;

    static synchronized boolean mongoAvailable() {
        if (mongoUp == null) {
            try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=500")) {
                client.getDatabase("admin").runCommand(new Document("ping", 1));
                mongoUp = true;
            } catch (Exception e) {
                mongoUp = false;
            }
        }
        return mongoUp;
    }

    static Map<String, Map<Integer, Integer>> index() {
        Map<String, Map<Integer, Integer>> index = new LinkedHashMap<>();
        index.put("island", new LinkedHashMap<>(Map.of(5, 3, 1342, 1)));
        index.put("adventure", new LinkedHashMap<>(Map.of(5, 2, 12, 1)));
        index.put("ship", new LinkedHashMap<>(Map.of(12, 4)));
        index.put(GREEK, new LinkedHashMap<>(Map.of(7, 1)));
        return index;
    }

    IndexStore open(String name) throws Exception {
        if (name.equals("mongo")) {
            assumeTrue(mongoAvailable(), "MongoDB no esta arrancado");
        }
        store = IndexStore.openStore(name, tmp.resolve("datamarts"), "mongodb://127.0.0.1:27017", "stage1_junit");
        return store;
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (store instanceof MongoStore) {
            ((MongoStore) store).drop();
        }
        if (store != null) {
            store.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "hier", "mongo", "sqlite"})
    void buildAndQuery(String name) throws Exception {
        IndexStore s = open(name);
        s.writeFull(index());
        s.open();
        assertEquals(Map.of(5, 3, 1342, 1), s.postings("island"));
        assertEquals(Map.of(), s.postings("missing"));
        assertEquals(Map.of(7, 1), s.postings(GREEK));
        assertEquals(List.of(5), IndexStore.searchIds(s, List.of("island", "adventure")));
        assertEquals(List.of(), IndexStore.searchIds(s, List.of("island", "ship")));
        assertEquals(List.of(), IndexStore.searchIds(s, List.of()));
        assertTrue(s.diskUsage() > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "hier", "mongo", "sqlite"})
    void addBookIsIdempotent(String name) throws Exception {
        IndexStore s = open(name);
        s.writeFull(index());
        s.open();
        s.addBook(99, Map.of("island", 2, "volcano", 1));
        s.addBook(99, Map.of("island", 5, "volcano", 1));
        assertEquals(Map.of(5, 3, 1342, 1, 99, 5), s.postings("island"));
        assertEquals(Map.of(99, 1), s.postings("volcano"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "hier", "mongo", "sqlite"})
    void termsExport(String name) throws Exception {
        IndexStore s = open(name);
        s.writeFull(index());
        s.open();
        Map<String, Map<Integer, Integer>> exported = new HashMap<>();
        s.terms((term, found) -> exported.put(term, new HashMap<>(found)));
        assertEquals(index(), exported);
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "hier", "sqlite"})
    void reopenAfterAdd(String name) throws Exception {
        IndexStore s = open(name);
        s.writeFull(index());
        s.open();
        s.addBook(3, Map.of("ship", 2));
        s.close();
        store = IndexStore.openStore(name, tmp.resolve("datamarts"), null, null);
        store.open();
        assertEquals(Map.of(12, 4, 3, 2), store.postings("ship"));
    }

    @Test
    void filenames() {
        assertEquals("adventure.txt", HierStore.termFilename("adventure"));
        assertEquals("con_.txt", HierStore.termFilename("con"));
        assertEquals("caf%C3%A9.txt", HierStore.termFilename("caf\u00E9"));
        assertEquals("_", HierStore.termFolder(GREEK));
        assertEquals("C", HierStore.termFolder("caf\u00E9"));
        String supplementary = new String(Character.toChars(0x1D504)) + "x";
        assertEquals("%F0%9D%94%84x.txt", HierStore.termFilename(supplementary));
        for (String term : List.of("caf\u00E9", "con", GREEK, "nul", supplementary)) {
            assertEquals(term, HierStore.filenameToTerm(HierStore.termFilename(term)));
        }
    }

    @Test
    void codePointOrder() {
        // U+FF41 va antes que U+1D504 por code point, pero despues en UTF-16
        String a = "\uFF41";
        String b = new String(Character.toChars(0x1D504));
        assertTrue(IndexStore.compareCodePoints(a, b) < 0);
        assertTrue(a.compareTo(b) > 0);
        List<String> list = new ArrayList<>(List.of("b", "ab", "a", ""));
        list.sort(IndexStore::compareCodePoints);
        assertEquals(List.of("", "a", "ab", "b"), list);
    }
}

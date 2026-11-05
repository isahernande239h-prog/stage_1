package es.ulpgc.bigdata.stage1;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** The whole index in a single JSON file: {"term": {"id": tf}}. */
public class JsonStore implements IndexStore {
    private final Path path;
    private final Path tmp;
    private Map<String, Map<Integer, Integer>> index;

    public JsonStore(Path datamarts) {
        path = datamarts.resolve("inverted_index.json");
        tmp = datamarts.resolve("inverted_index.tmp");
    }

    @Override
    public void writeFull(Map<String, Map<Integer, Integer>> index) throws IOException {
        save(index);
        this.index = null;
    }

    private void save(Map<String, Map<Integer, Integer>> data) throws IOException {
        // compact and without escaping non-ASCII characters, same as json.dump in Python
        try (JsonWriter out = new JsonWriter(Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
            out.beginObject();
            for (Map.Entry<String, Map<Integer, Integer>> e : data.entrySet()) {
                out.name(e.getKey());
                out.beginObject();
                for (Map.Entry<Integer, Integer> p : e.getValue().entrySet()) {
                    out.name(String.valueOf(p.getKey())).value(p.getValue());
                }
                out.endObject();
            }
            out.endObject();
        }
        FileUtil.replace(tmp, path);
    }

    @Override
    public void open() throws IOException {
        index = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return;
        }
        try (JsonReader in = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            in.beginObject();
            while (in.hasNext()) {
                String term = in.nextName();
                Map<Integer, Integer> found = new LinkedHashMap<>();
                in.beginObject();
                while (in.hasNext()) {
                    found.put(Integer.parseInt(in.nextName()), in.nextInt());
                }
                in.endObject();
                index.put(term, found);
            }
            in.endObject();
        }
    }

    @Override
    public void addBook(int bookId, Map<String, Integer> tfs) throws IOException {
        // there is no way to change just part of the file: it gets rewritten entirely
        if (index == null) {
            open();
        }
        for (Map.Entry<String, Integer> e : tfs.entrySet()) {
            index.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).put(bookId, e.getValue());
        }
        save(index);
    }

    @Override
    public Map<Integer, Integer> postings(String term) {
        Map<Integer, Integer> found = index.get(term);
        return found != null ? found : Map.of();
    }

    @Override
    public void terms(BiConsumer<String, Map<Integer, Integer>> action) {
        index.forEach(action);
    }

    @Override
    public long diskUsage() throws IOException {
        return Files.size(path);
    }

    @Override
    public void close() {
        index = null;
    }
}

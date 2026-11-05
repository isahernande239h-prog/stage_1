package es.ulpgc.bigdata.stage1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** One file per term: inverted_index/<L>/<term>.txt with lines "id<TAB>tf". */
public class HierStore implements IndexStore {
    static final Set<String> RESERVED = new HashSet<>(Arrays.asList("con", "prn", "aux", "nul"));
    static final String HEX = "0123456789ABCDEF";

    static {
        for (int i = 1; i <= 9; i++) {
            RESERVED.add("com" + i);
            RESERVED.add("lpt" + i);
        }
    }

    private final Path root;

    public HierStore(Path datamarts) {
        root = datamarts.resolve("inverted_index");
    }

    static String termFolder(String term) {
        char first = term.charAt(0);
        return first >= 'a' && first <= 'z' ? String.valueOf(Character.toUpperCase(first)) : "_";
    }

    static String termFilename(String term) {
        // a-z as they are and the rest as %XX: the Windows and macOS file systems are not
        // case sensitive, and two Greek terms that only differ in the final sigma would clash
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                name.append(ch);
                continue;
            }
            int cp = term.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                name.append('%').append(HEX.charAt((b >> 4) & 0xF)).append(HEX.charAt(b & 0xF));
            }
            i += Character.charCount(cp) - 1;
        }
        if (RESERVED.contains(name.toString())) {
            name.append('_');
        }
        return name.append(".txt").toString();
    }

    static String filenameToTerm(String filename) {
        String name = filename.substring(0, filename.length() - 4);
        if (name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int i = 0;
        while (i < name.length()) {
            if (name.charAt(i) == '%') {
                raw.write(Integer.parseInt(name.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                raw.write(name.charAt(i));
                i++;
            }
        }
        return raw.toString(StandardCharsets.UTF_8);
    }

    private Path path(String term) {
        return root.resolve(termFolder(term)).resolve(termFilename(term));
    }

    private void makeFolders() throws IOException {
        for (char c = 'A'; c <= 'Z'; c++) {
            Files.createDirectories(root.resolve(String.valueOf(c)));
        }
        Files.createDirectories(root.resolve("_"));
    }

    @Override
    public void writeFull(Map<String, Map<Integer, Integer>> index) throws IOException {
        makeFolders();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<Integer, Integer>> e : index.entrySet()) {
            sb.setLength(0);
            for (Map.Entry<Integer, Integer> p : e.getValue().entrySet()) {
                sb.append(p.getKey()).append('\t').append(p.getValue()).append('\n');
            }
            Files.writeString(path(e.getKey()), sb, StandardCharsets.UTF_8);
        }
    }

    @Override
    public void open() throws IOException {
        makeFolders();
    }

    @Override
    public void addBook(int bookId, Map<String, Integer> tfs) throws IOException {
        for (Map.Entry<String, Integer> e : tfs.entrySet()) {
            FileUtil.append(path(e.getKey()), bookId + "\t" + e.getValue() + "\n");
        }
    }

    private static Map<Integer, Integer> read(Path path) throws IOException {
        Map<Integer, Integer> found = new LinkedHashMap<>();
        for (String line : FileUtil.readText(path).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            // if a book shows up twice, the last line wins
            found.put(Integer.parseInt(line.substring(0, tab)), Integer.parseInt(line.substring(tab + 1)));
        }
        return found;
    }

    @Override
    public Map<Integer, Integer> postings(String term) throws IOException {
        Path path = path(term);
        return Files.exists(path) ? read(path) : Map.of();
    }

    @Override
    public void terms(BiConsumer<String, Map<Integer, Integer>> action) throws IOException {
        String[] folders = root.toFile().list();
        Arrays.sort(folders);
        for (String folder : folders) {
            String[] files = root.resolve(folder).toFile().list();
            for (String file : files) {
                action.accept(filenameToTerm(file), read(root.resolve(folder).resolve(file)));
            }
        }
    }

    @Override
    public long diskUsage() throws IOException {
        // allocated bytes (blocks), which is what matters with half a million files
        long block = FileUtil.blockSize(root);
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            Iterator<Path> it = walk.iterator();
            while (it.hasNext()) {
                Path path = it.next();
                if (Files.isRegularFile(path)) {
                    total += FileUtil.allocated(Files.size(path), block);
                }
            }
        }
        return total;
    }

    @Override
    public void close() {
    }
}

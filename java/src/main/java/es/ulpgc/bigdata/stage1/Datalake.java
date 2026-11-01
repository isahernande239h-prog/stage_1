package es.ulpgc.bigdata.stage1;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Datalake con tres organizaciones posibles: time, book y range (ver spec/pipeline_spec.md). */
public class Datalake {
    public static final List<String> LAYOUTS = List.of("time", "book", "range");
    public static final String REGISTRY = "_registry.tsv";
    static final DateTimeFormatter HOUR_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd/HH");

    final Path root;
    final String layout;
    Supplier<ZonedDateTime> clock;
    private Map<Integer, String> registry;

    public Datalake(Path root, String layout) {
        this(root, layout, Datalake::utcNow);
    }

    public Datalake(Path root, String layout, Supplier<ZonedDateTime> clock) {
        if (!LAYOUTS.contains(layout)) {
            throw new IllegalArgumentException("layout desconocido: " + layout);
        }
        this.root = root;
        this.layout = layout;
        this.clock = clock;
    }

    public static ZonedDateTime utcNow() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    // ---------- rutas ----------

    public String partitionFor(int bookId) {
        if (layout.equals("time")) {
            return clock.get().format(HOUR_FOLDER);
        }
        if (layout.equals("book")) {
            return String.valueOf(bookId);
        }
        return String.format("%06d", bookId / 1000 * 1000);
    }

    String[] fileNames(int bookId) {
        if (layout.equals("book")) {
            return new String[] {"header.txt", "body.txt"};
        }
        return new String[] {bookId + ".header.txt", bookId + ".body.txt"};
    }

    /** {cabecera, cuerpo} de un libro en una particion. */
    public Path[] paths(int bookId, String partition) {
        Path folder = root.resolve(partition);
        String[] names = fileNames(bookId);
        return new Path[] {folder.resolve(names[0]), folder.resolve(names[1])};
    }

    // ---------- escritura ----------

    public String writeBook(int bookId, String header, String body) throws IOException {
        return writeBook(bookId, header, body, false);
    }

    /** Escribe los dos ficheros con tmp + rename. Devuelve la particion usada. */
    public String writeBook(int bookId, String header, String body, boolean crashBetweenRenames) throws IOException {
        String partition = partitionFor(bookId);
        Path[] paths = paths(bookId, partition);
        Path headerPath = paths[0];
        Path bodyPath = paths[1];
        Files.createDirectories(headerPath.getParent());
        Path headerTmp = headerPath.resolveSibling(headerPath.getFileName() + ".tmp");
        Path bodyTmp = bodyPath.resolveSibling(bodyPath.getFileName() + ".tmp");
        Files.write(bodyTmp, body.getBytes(StandardCharsets.UTF_8));
        Files.write(headerTmp, header.getBytes(StandardCharsets.UTF_8));
        FileUtil.replace(bodyTmp, bodyPath);
        if (crashBetweenRenames) {
            throw new SimulatedCrashException("entre renombrados");
        }
        FileUtil.replace(headerTmp, headerPath);
        if (layout.equals("time")) {
            FileUtil.append(root.resolve(REGISTRY), bookId + "\t" + partition + "\n");
            if (registry != null) {
                registry.put(bookId, partition);
            }
        }
        return partition;
    }

    // ---------- lectura ----------

    public Map<Integer, String> loadRegistry() throws IOException {
        Map<Integer, String> result = new HashMap<>();
        for (String line : Control.readLines(root.resolve(REGISTRY))) {
            int tab = line.indexOf('\t');
            String id = tab < 0 ? line : line.substring(0, tab);
            String partition = tab < 0 ? "" : line.substring(tab + 1);
            result.put(Integer.parseInt(id.strip()), partition);  // si se repite, vale la ultima
        }
        registry = result;
        return result;
    }

    /** {cabecera, cuerpo} de un libro completo, o null. */
    public Path[] locate(int bookId) throws IOException {
        String partition;
        if (layout.equals("time")) {
            if (registry == null) {
                loadRegistry();
            }
            partition = registry.get(bookId);
            if (partition == null) {
                return null;
            }
        } else {
            partition = partitionFor(bookId);
        }
        Path[] paths = paths(bookId, partition);
        if (Files.isRegularFile(paths[0]) && Files.isRegularFile(paths[1])) {
            return paths;
        }
        return null;
    }

    /** {cabecera, cuerpo} como texto. */
    public String[] readBook(int bookId) throws IOException {
        Path[] found = locate(bookId);
        if (found == null) {
            throw new IOException("libro " + bookId + " no esta en el datalake");
        }
        return new String[] {FileUtil.readText(found[0]), FileUtil.readText(found[1])};
    }

    public String relative(Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    // ---------- recorrido completo ----------

    private static String[] sortedNames(Path folder) {
        String[] names = folder.toFile().list();
        if (names == null) {
            return new String[0];
        }
        Arrays.sort(names);
        return names;
    }

    private List<String> partitions(String minPartition) {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        if (!layout.equals("time")) {
            for (String name : sortedNames(root)) {
                if (Files.isDirectory(root.resolve(name))) {
                    result.add(name);
                }
            }
            return result;
        }
        for (String day : sortedNames(root)) {
            if (!Files.isDirectory(root.resolve(day))) {
                continue;
            }
            if (minPartition != null && day.compareTo(minPartition.substring(0, Math.min(8, minPartition.length()))) < 0) {
                continue;
            }
            for (String hour : sortedNames(root.resolve(day))) {
                String partition = day + "/" + hour;
                if (Files.isDirectory(root.resolve(partition))
                        && (minPartition == null || partition.compareTo(minPartition) >= 0)) {
                    result.add(partition);
                }
            }
        }
        return result;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** "header" o "body" si el fichero es de un libro, si no null. */
    private String kindOf(String partition, String name) {
        if (layout.equals("book")) {
            if ((name.equals("header.txt") || name.equals("body.txt")) && isDigits(partition)) {
                return name.substring(0, name.length() - 4);
            }
            return null;
        }
        String[] parts = name.split("\\.", -1);
        if (parts.length == 3 && isDigits(parts[0]) && (parts[1].equals("header") || parts[1].equals("body"))
                && parts[2].equals("txt")) {
            return parts[1];
        }
        return null;
    }

    private int idOf(String partition, String name) {
        return Integer.parseInt(layout.equals("book") ? partition : name.substring(0, name.indexOf('.')));
    }

    public ScanResult scan() {
        return scan(null);
    }

    public ScanResult scan(String minPartition) {
        ScanResult result = new ScanResult();
        for (String partition : partitions(minPartition)) {
            Path folder = root.resolve(partition);
            Map<Integer, Map<String, Path>> found = new LinkedHashMap<>();
            String[] names = folder.toFile().list();
            if (names == null) {
                continue;
            }
            for (String name : names) {
                Path path = folder.resolve(name);
                if (name.endsWith(".tmp")) {
                    result.temps.add(path);
                    continue;
                }
                String kind = kindOf(partition, name);
                if (kind != null) {
                    found.computeIfAbsent(idOf(partition, name), k -> new HashMap<>()).put(kind, path);
                }
            }
            for (Map.Entry<Integer, Map<String, Path>> e : found.entrySet()) {
                if (e.getValue().size() == 2) {
                    result.complete.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(partition);
                } else {
                    result.orphans.addAll(e.getValue().values());
                }
            }
        }
        return result;
    }

    // ---------- recuperacion ----------

    /** Deja el datalake y downloaded_books.txt consistentes despues de una caida. */
    public Map<String, Integer> reconcile(Control control) throws IOException {
        ScanResult scan = scan();
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("temps", scan.temps.size());
        stats.put("orphans", scan.orphans.size());
        for (Path path : scan.temps) {
            Files.deleteIfExists(path);
        }
        for (Path path : scan.orphans) {
            Files.deleteIfExists(path);
        }

        Map<Integer, String> registered = layout.equals("time") ? loadRegistry() : new HashMap<>();
        int duplicates = 0;
        for (Map.Entry<Integer, List<String>> e : scan.complete.entrySet()) {
            List<String> partitions = e.getValue();
            if (partitions.size() > 1) {
                String keep = registered.get(e.getKey());
                if (keep == null || !partitions.contains(keep)) {
                    keep = Collections.max(partitions);
                }
                for (String partition : partitions) {
                    if (!partition.equals(keep)) {
                        for (Path path : paths(e.getKey(), partition)) {
                            Files.deleteIfExists(path);
                        }
                        duplicates++;
                    }
                }
                e.setValue(new ArrayList<>(List.of(keep)));
            }
        }
        stats.put("duplicates", duplicates);

        if (layout.equals("time")) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, List<String>> e : new TreeMap<>(scan.complete).entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue().get(0)).append('\n');
            }
            Path tmp = root.resolve(REGISTRY + ".tmp");
            Files.writeString(tmp, sb, StandardCharsets.UTF_8);
            FileUtil.replace(tmp, root.resolve(REGISTRY));
            registry = null;
        }

        Set<Integer> complete = scan.ids();
        Set<Integer> inControl = control.downloaded();
        int added = 0;
        int removed = 0;
        for (int id : complete) {
            if (!inControl.contains(id)) {
                added++;
            }
        }
        for (int id : inControl) {
            if (!complete.contains(id)) {
                removed++;
            }
        }
        stats.put("added_to_control", added);
        stats.put("removed_from_control", removed);
        if (!complete.equals(inControl)) {
            control.rewriteDownloaded(complete);
        }
        return stats;
    }

    // ---------- espacio ocupado ----------

    public Map<String, Long> storageStats() throws IOException {
        long files = 0;
        long dirs = 0;
        long size = 0;
        long allocated = 0;
        if (Files.isDirectory(root)) {
            long block = FileUtil.blockSize(root);
            try (Stream<Path> walk = Files.walk(root)) {
                Iterator<Path> it = walk.iterator();
                while (it.hasNext()) {
                    Path path = it.next();
                    if (path.equals(root)) {
                        continue;
                    }
                    if (Files.isDirectory(path)) {
                        dirs++;
                        continue;
                    }
                    if (path.getParent().equals(root) && path.getFileName().toString().equals(REGISTRY)) {
                        continue;
                    }
                    long bytes = Files.size(path);
                    files++;
                    size += bytes;
                    allocated += FileUtil.allocated(bytes, block);
                }
            }
        }
        Path registryFile = root.resolve(REGISTRY);
        long aux = Files.exists(registryFile) ? Files.size(registryFile) : 0;
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("files", files);
        stats.put("dirs", dirs);
        stats.put("bytes", size);
        stats.put("allocated_bytes", allocated);
        stats.put("aux_bytes", aux);
        return stats;
    }
}

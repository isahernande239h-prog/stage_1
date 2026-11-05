package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Access to the shared files in spec/ (the same for the three languages). */
public final class Spec {
    private static Path dir;

    private Spec() {
    }

    /** STAGE1_SPEC, or the first spec/ folder with pipeline_spec.md going up from the jar or from the current directory. */
    public static synchronized Path dir() {
        if (dir != null) {
            return dir;
        }
        String env = System.getenv("STAGE1_SPEC");
        if (env != null && !env.isEmpty()) {
            dir = Path.of(env);
            return dir;
        }
        List<Path> starts = new ArrayList<>();
        try {
            starts.add(Path.of(Spec.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath());
        } catch (Exception e) {
            // without the jar location we only try from the current directory
        }
        starts.add(Path.of("").toAbsolutePath());
        for (Path start : starts) {
            for (Path folder = start; folder != null; folder = folder.getParent()) {
                Path candidate = folder.resolve("spec");
                if (Files.isRegularFile(candidate.resolve("pipeline_spec.md"))) {
                    dir = candidate;
                    return dir;
                }
            }
        }
        throw new IllegalStateException("cannot find the spec/ folder (set STAGE1_SPEC to point to it)");
    }

    static List<String> lines(Path path) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : FileUtil.readText(path).split("\n")) {
            String clean = line.strip();
            if (!clean.isEmpty() && !line.startsWith("#")) {
                result.add(clean);
            }
        }
        return result;
    }

    public static Set<String> loadStopwords() throws IOException {
        return new HashSet<>(lines(dir().resolve("stopwords.txt")));
    }

    public static Map<String, String> loadLanguages() throws IOException {
        Map<String, String> table = new HashMap<>();
        for (String line : lines(dir().resolve("languages.tsv"))) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 2) {
                throw new IOException("malformed line in languages.tsv: " + line);
            }
            table.put(parts[0], parts[1]);
        }
        return table;
    }

    public static List<String> loadQueries(Path path) throws IOException {
        return lines(path != null ? path : dir().resolve("queries.txt"));
    }

    public static List<Integer> loadIds(Path path) throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (String line : lines(path != null ? path : dir().resolve("corpus_ids.txt"))) {
            ids.add(Integer.parseInt(line));
        }
        return ids;
    }
}

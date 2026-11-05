package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Control files: which books are downloaded, indexed or have failed. */
public class Control {
    final Path folder;
    final Path downloadedFile;
    final Path indexedFile;
    final Path failedFile;

    public Control(Path folder) throws IOException {
        this.folder = folder;
        Files.createDirectories(folder);
        downloadedFile = folder.resolve("downloaded_books.txt");
        indexedFile = folder.resolve("indexed_books.txt");
        failedFile = folder.resolve("failed_books.txt");
    }

    /** Complete lines of the file. A last line without a newline is ignored (cut-off write). */
    public static List<String> readLines(Path path) throws IOException {
        List<String> result = new ArrayList<>();
        if (!Files.exists(path)) {
            return result;
        }
        String[] lines = FileUtil.readText(path).split("\n", -1);
        // the last item is whatever comes after the last \n: empty or incomplete
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].isBlank()) {
                result.add(lines[i]);
            }
        }
        return result;
    }

    public Set<Integer> downloaded() throws IOException {
        return readIds(downloadedFile);
    }

    public Set<Integer> indexed() throws IOException {
        return readIds(indexedFile);
    }

    public Map<Integer, String> failed() throws IOException {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (String line : readLines(failedFile)) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                result.put(Integer.parseInt(line.strip()), "");
            } else {
                result.put(Integer.parseInt(line.substring(0, tab).strip()), line.substring(tab + 1));
            }
        }
        return result;
    }

    /** Downloaded but not indexed yet. */
    public Set<Integer> pending() throws IOException {
        Set<Integer> result = downloaded();
        result.removeAll(indexed());
        return result;
    }

    public void markDownloaded(int bookId) throws IOException {
        FileUtil.append(downloadedFile, bookId + "\n");
    }

    public void markIndexed(int bookId) throws IOException {
        FileUtil.append(indexedFile, bookId + "\n");
    }

    public void markFailed(int bookId, String reason) throws IOException {
        FileUtil.append(failedFile, bookId + "\t" + reason + "\n");
    }

    public void rewriteDownloaded(Collection<Integer> ids) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int id : new TreeSet<>(ids)) {
            sb.append(id).append('\n');
        }
        Path tmp = folder.resolve("downloaded_books.tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        FileUtil.replace(tmp, downloadedFile);
    }

    private static Set<Integer> readIds(Path path) throws IOException {
        Set<Integer> ids = new HashSet<>();
        for (String line : readLines(path)) {
            ids.add(Integer.parseInt(line.strip()));
        }
        return ids;
    }
}

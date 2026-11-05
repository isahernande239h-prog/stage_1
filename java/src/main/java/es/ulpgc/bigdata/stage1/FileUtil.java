package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Small file helpers used by several classes. */
final class FileUtil {
    private FileUtil() {
    }

    static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Like Python's read_text(): UTF-8, and \r\n and \r line breaks become \n. */
    static String readText(Path path) throws IOException {
        return normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Appends text with a single write: if the process dies, at most one line is left half written. */
    static void append(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Same as os.replace: renames, overwriting the target if it exists. */
    static void replace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    static long blockSize(Path path) throws IOException {
        return Files.getFileStore(path).getBlockSize();
    }

    /** Bytes allocated on disk. Java doesn't give st_blocks, so it rounds up to the block size. */
    static long allocated(long size, long blockSize) {
        return (size + blockSize - 1) / blockSize * blockSize;
    }
}

package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Pequenas utilidades de ficheros que usan varias clases. */
final class FileUtil {
    private FileUtil() {
    }

    static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Como read_text() de Python: UTF-8 y los saltos \r\n y \r pasan a \n. */
    static String readText(Path path) throws IOException {
        return normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Anade texto al final con una sola escritura: si el proceso muere, como mucho queda una linea a medias. */
    static void append(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Igual que os.replace: renombra pisando el destino si existe. */
    static void replace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    static long blockSize(Path path) throws IOException {
        return Files.getFileStore(path).getBlockSize();
    }

    /** Bytes asignados en disco. Java no da st_blocks, asi que se redondea al tamano de bloque. */
    static long allocated(long size, long blockSize) {
        return (size + blockSize - 1) / blockSize * blockSize;
    }
}

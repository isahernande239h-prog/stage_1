package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lee pg<id>.txt de una carpeta. Sirve para repetir benchmarks sin volver a descargar. */
public class CacheSource implements Source {
    private final Path folder;

    public CacheSource(Path folder) {
        this.folder = folder;
    }

    @Override
    public String fetch(int bookId) throws IOException {
        Path path = folder.resolve("pg" + bookId + ".txt");
        if (!Files.exists(path)) {
            throw new BookNotFoundException(bookId);
        }
        return Source.decode(Files.readAllBytes(path));
    }
}

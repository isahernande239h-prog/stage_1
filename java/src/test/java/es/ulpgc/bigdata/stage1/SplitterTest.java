package es.ulpgc.bigdata.stage1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitterTest {
    static final String BOOK = "The Project Gutenberg eBook of Test\r\n"
            + "Title: Test\r\n"
            + "\r\n"
            + "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\r\n"
            + "\r\n"
            + "Chapter 1\r\n"
            + "Some text.\r\n"
            + "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\r\n"
            + "License stuff\r\n";

    @Test
    void basicSplit() throws Exception {
        String[] parts = Splitter.split(BOOK);
        assertEquals("The Project Gutenberg eBook of Test\nTitle: Test", parts[0]);
        assertEquals("Chapter 1\nSome text.", parts[1]);
    }

    @Test
    void variantsThisAndEbookWithDash() throws Exception {
        String text = "h\n***START OF THIS PROJECT GUTENBERG E-BOOK X\nbody\n*** end of this project gutenberg e-book X\nf";
        assertArrayEquals(new String[] {"h", "body"}, Splitter.split(text));
    }

    @Test
    void missingEndMarker() {
        assertThrows(NoMarkersException.class,
                () -> Splitter.split("header\n*** START OF THE PROJECT GUTENBERG EBOOK X\nbody only\n"));
    }

    @Test
    void markerMustBeAtLineStart() {
        String text = "h\nsee *** START OF THE PROJECT GUTENBERG EBOOK here\nbody\n";
        assertThrows(NoMarkersException.class, () -> Splitter.split(text));
    }

    @Test
    void endMarkerBeforeStartDoesNotCount() {
        String text = "*** END OF THE PROJECT GUTENBERG EBOOK X\nh\n*** START OF THE PROJECT GUTENBERG EBOOK X\nbody\n";
        assertThrows(NoMarkersException.class, () -> Splitter.split(text));
    }

    @Test
    void looseAsterisksInsideBodyAreIgnored() throws Exception {
        String text = "h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n"
                + "a\n***\nb\n*** END OF THE PROJECT GUTENBERG EBOOK X\n";
        assertEquals("a\n***\nb", Splitter.split(text)[1]);
    }

    @Test
    void onlyAsciiWhitespaceIsTrimmed() throws Exception {
        String text = "h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n\u00A0body\u00A0\n*** END OF THE PROJECT GUTENBERG EBOOK X\n";
        assertEquals("\u00A0body\u00A0", Splitter.split(text)[1]);
        String other = "h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n \u000B\u2028body\u3000\f\n*** END OF THE PROJECT GUTENBERG EBOOK X\n";
        assertEquals("\u2028body\u3000", Splitter.split(other)[1]);
    }

    @Test
    void markersAreAsciiCaseInsensitiveOnly() {
        // la s larga (U+017F) no vale como "s" en ASCII
        String text = "h\n*** \u017FTART OF THE PROJECT GUTENBERG EBOOK X\nb\n*** END OF THE PROJECT GUTENBERG EBOOK X\n";
        assertThrows(NoMarkersException.class, () -> Splitter.split(text));
    }

    @Test
    void decodeRemovesBom() {
        assertEquals("hola", Source.decode("\uFEFFhola".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodeReplacesLikePython() {
        // mismos resultados que bytes.decode("utf-8", errors="replace")
        assertEquals("\uFFFD\uFFFD\uFFFD", Source.decode(HexFormat.of().parseHex("eda080")));
        assertEquals("\uFFFD\uFFFD\uFFFD", Source.decode(HexFormat.of().parseHex("e08080")));
        assertEquals("a\uFFFDb", Source.decode(HexFormat.of().parseHex("61e28262")));
        assertEquals("\uFFFD\u00E9", Source.decode(HexFormat.of().parseHex("c2c3a9")));
        assertEquals("\uFFFDa", Source.decode(HexFormat.of().parseHex("f09f9861")));
        assertEquals("x\uD83D\uDE00\uFFFD", Source.decode(HexFormat.of().parseHex("78f09f9880ff")));
    }

    @Test
    void cacheSource(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("pg5.txt"), "\uFEFFtexto".getBytes(StandardCharsets.UTF_8));
        Source source = Source.open("cache:" + tmp);
        assertEquals("texto", source.fetch(5));
        assertThrows(BookNotFoundException.class, () -> source.fetch(6));
    }
}

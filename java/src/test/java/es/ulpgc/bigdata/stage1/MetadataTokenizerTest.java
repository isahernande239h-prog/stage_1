package es.ulpgc.bigdata.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataTokenizerTest {
    static final String HEADER = String.join("\n",
            "The Project Gutenberg eBook of Pride and Prejudice",
            "",
            "This ebook is for the use of anyone anywhere in the United States and",
            "most other parts of the world at no cost and with almost no restrictions",
            "whatsoever.",
            "",
            "Title: Pride and Prejudice",
            "",
            "Author: Jane Austen",
            "",
            "Release date: June 1, 1998 [eBook #1342]",
            "                Most recently updated: October 29, 2024",
            "",
            "Language: English",
            "",
            "Original publication: London: T. Egerton, 1813",
            "",
            "Credits: Chuck Greif");

    @Test
    void parseHeader() {
        assertEquals(new BookMeta("Pride and Prejudice", "Jane Austen", "en", 1813, 1998), HeaderParser.parseHeader(HEADER));
    }

    @Test
    void continuationLines() {
        String header = "Title: The Life and Adventures\n   of Robinson Crusoe\n\n"
                + "Author: First Author\n        Second Author\n\nLanguage: French and English";
        BookMeta meta = HeaderParser.parseHeader(header);
        assertEquals("The Life and Adventures of Robinson Crusoe", meta.title());
        assertEquals("First Author; Second Author", meta.author());
        assertEquals("fr", meta.language());
    }

    @Test
    void missingFieldsAreNull() {
        BookMeta meta = HeaderParser.parseHeader("Title: Only a title\nLanguage: Klingon");
        assertNull(meta.author());
        assertNull(meta.year());
        assertNull(meta.language());
    }

    @Test
    void releaseYearIgnoresEbookNumber() {
        assertEquals(2004, HeaderParser.parseHeader("Release Date: [EBook #1234] March, 2004").releaseYear());
    }

    @Test
    void severalAuthorFieldsAndOddSpaces() {
        String header = "Author: A  \t B\n\t\nAuthor: C\nTitle:\u2028x\u0085 y";
        BookMeta meta = HeaderParser.parseHeader(header);
        assertEquals("A B; C", meta.author());
        assertEquals("\u2028x\u0085 y", meta.title());
    }

    @Test
    void lastYearOfOriginalPublication() {
        BookMeta meta = HeaderParser.parseHeader("Original publication: 1850-1852, 12345 copies; 1899");
        assertEquals(1899, meta.year());
        assertNull(meta.releaseYear());
    }

    @Test
    void storeUpsertDoesNotDuplicate(@TempDir Path tmp) throws Exception {
        try (MetadataStore store = new MetadataStore(tmp.resolve("metadata.db"))) {
            BookMeta meta = HeaderParser.parseHeader(HEADER);
            store.upsert(1342, meta, "001000/1342.header.txt", "001000/1342.body.txt");
            store.upsert(1342, meta, "001000/1342.header.txt", "001000/1342.body.txt");
            assertEquals(1, store.all().size());
            assertEquals("Jane Austen", store.get(1342).get("author"));
            assertEquals(1342, store.byAuthor("austen").get(0).get("book_id"));

            store.upsert(7, new BookMeta(null, null, null, null, null), "a", "b");
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("book_id", 7);
            expected.put("title", null);
            expected.put("author", null);
            expected.put("language", null);
            expected.put("year", null);
            expected.put("release_year", null);
            expected.put("header_path", "a");
            expected.put("body_path", "b");
            assertEquals(expected, store.get(7));
            assertNull(store.get(8));
        }
    }

    @Test
    void tokenizeRules() {
        String text = "The Whale! a whale's TALE, caf\u00E9-au-lait 1851 x \u0394\u03AD\u03BB\u03C4\u03B1";
        assertEquals(List.of("whale", "whale", "tale", "caf\u00E9", "au", "lait", "\u03B4\u03AD\u03BB\u03C4\u03B1"),
                Tokenizer.tokenize(text));
    }

    @Test
    void unicodeLettersAndLengthInCodePoints() {
        // U+1D504 is a letter outside the BMP: it counts as one character
        String a = new String(Character.toChars(0x1D504));
        String b = new String(Character.toChars(0x1D505));
        assertEquals(List.of(a + b), Tokenizer.tokenize(a + b + " " + a));
    }

    @Test
    void finalSigmaAndDottedCapitalI() {
        // final sigma when lowercasing; the dotted I gives i + U+0307, which is not a letter
        assertEquals(List.of("\u03BF\u03B4\u03BF\u03C2"), Tokenizer.tokenize("\u039F\u0394\u039F\u03A3"));
        assertEquals(List.of("abi"), Tokenizer.tokenize("AB\u0130C"));
    }

    @Test
    void termFrequencies() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("sea", 2);
        expected.put("ship", 1);
        assertEquals(expected, Tokenizer.termFrequencies("sea sea ship the"));
    }
}

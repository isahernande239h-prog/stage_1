package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the "Name: value" fields of the Gutenberg header. */
public final class HeaderParser {
    // DOTALL so that (.*) also takes U+2028, U+0085... like the dot in Python
    static final Pattern FIELD = Pattern.compile("([A-Za-z][A-Za-z -]*):[ \\t]*(.*)", Pattern.DOTALL);
    static final Pattern YEAR = Pattern.compile("(?<![0-9])[0-9]{4}(?![0-9])");
    static final Pattern SPACES = Pattern.compile("[ \\t]+");
    static final Pattern LANG_SPLIT = Pattern.compile(",|;| and ", Pattern.CASE_INSENSITIVE);
    static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private static Map<String, String> languages;

    private HeaderParser() {
    }

    private static synchronized Map<String, String> languages() {
        if (languages == null) {
            try {
                languages = Spec.loadLanguages();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return languages;
    }

    static String clean(String value) {
        String s = SPACES.matcher(value).replaceAll(" ");
        int from = 0;
        int to = s.length();
        while (from < to && (s.charAt(from) == ' ' || s.charAt(from) == '\t')) {
            from++;
        }
        while (to > from && (s.charAt(to - 1) == ' ' || s.charAt(to - 1) == '\t')) {
            to--;
        }
        return s.substring(from, to);
    }

    /** {lowercase name: [values]}. Each value is a list of lines (the first one + continuations). */
    public static Map<String, List<List<String>>> headerFields(String header) {
        Map<String, List<List<String>>> fields = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : header.split("\n", -1)) {
            boolean indented = !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
            if (indented && current != null) {
                String value = clean(line);
                if (!value.isEmpty()) {
                    current.add(value);
                    continue;
                }
            }
            Matcher m = FIELD.matcher(line);
            if (m.matches()) {
                current = new ArrayList<>();
                current.add(clean(m.group(2)));
                fields.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(current);
            } else {
                current = null;
            }
        }
        return fields;
    }

    public static BookMeta parseHeader(String header) {
        Map<String, List<List<String>>> fields = headerFields(header);

        String title = null;
        if (fields.containsKey("title")) {
            List<String> parts = new ArrayList<>();
            for (String x : fields.get("title").get(0)) {
                if (!x.isEmpty()) {
                    parts.add(x);
                }
            }
            title = parts.isEmpty() ? null : String.join(" ", parts);
        }

        List<String> authors = new ArrayList<>();
        for (List<String> value : fields.getOrDefault("author", List.of())) {
            for (String x : value) {
                if (!x.isEmpty()) {
                    authors.add(x);
                }
            }
        }
        String author = authors.isEmpty() ? null : String.join("; ", authors);

        String language = null;
        if (fields.containsKey("language")) {
            String text = String.join(" ", fields.get("language").get(0));
            Matcher sep = LANG_SPLIT.matcher(text);
            String first = sep.find() ? text.substring(0, sep.start()) : text;
            language = languages().get(clean(first).toLowerCase(Locale.ROOT));
        }

        Integer year = null;
        if (fields.containsKey("original publication")) {
            Matcher m = YEAR.matcher(String.join(" ", fields.get("original publication").get(0)));
            while (m.find()) {
                year = Integer.parseInt(m.group());   // the last one wins
            }
        }

        Integer releaseYear = null;
        if (fields.containsKey("release date")) {
            String text = BRACKETS.matcher(String.join(" ", fields.get("release date").get(0))).replaceAll("");
            Matcher m = YEAR.matcher(text);
            if (m.find()) {
                releaseYear = Integer.parseInt(m.group());
            }
        }

        return new BookMeta(title, author, language, year, releaseYear);
    }
}

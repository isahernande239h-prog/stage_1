package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared tokenizer: lowercase, runs of unicode letters, length >= 2 and no stop words. */
public final class Tokenizer {
    private static Set<String> stopwords;

    private Tokenizer() {
    }

    public static synchronized Set<String> stopwords() {
        if (stopwords == null) {
            try {
                stopwords = Spec.loadStopwords();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return stopwords;
    }

    public static List<String> tokenize(String text) {
        return tokenize(text, stopwords());
    }

    public static List<String> tokenize(String text, Set<String> stopwords) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        int n = lower.length();
        int i = 0;
        while (i < n) {
            int cp = lower.codePointAt(i);
            if (!Character.isLetter(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            // longest run of letters; the length is counted in code points, not chars
            int start = i;
            int letters = 0;
            while (i < n) {
                cp = lower.codePointAt(i);
                if (!Character.isLetter(cp)) {
                    break;
                }
                i += Character.charCount(cp);
                letters++;
            }
            if (letters >= 2) {
                String token = lower.substring(start, i);
                if (!stopwords.contains(token)) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    /** {term: count}, in the order each term first shows up (like Counter). */
    public static Map<String, Integer> termFrequencies(String text) {
        return termFrequencies(text, stopwords());
    }

    public static Map<String, Integer> termFrequencies(String text, Set<String> stopwords) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokenize(text, stopwords)) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }
}

package es.ulpgc.bigdata.stage1;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a Gutenberg book into header and body. */
public final class Splitter {
    // The markers change a bit between books (THE/THIS, EBOOK/E-BOOK, with or without a space
    // after the asterisks), so we don't look for the exact string from the assignment.
    // CASE_INSENSITIVE without UNICODE_CASE only compares ASCII letters, like re.ASCII in Python.
    static final Pattern START = Pattern.compile(
            "\\*\\*\\*[ \\t]*START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", Pattern.CASE_INSENSITIVE);
    static final Pattern END = Pattern.compile(
            "\\*\\*\\*[ \\t]*END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", Pattern.CASE_INSENSITIVE);

    // trim() and strip() remove more characters and would give different results from Python/JS
    static final String ASCII_WS = " \t\n\r\f\u000B";

    private Splitter() {
    }

    /** Returns {header, body}. Throws NoMarkersException if the two markers aren't there. */
    public static String[] split(String text) throws NoMarkersException {
        text = FileUtil.normalizeNewlines(text);
        Matcher start = START.matcher(text);
        Matcher end = END.matcher(text);
        int startLine = -1;     // start and end of the START line
        int startLineEnd = -1;
        int endOffset = -1;
        int pos = 0;
        while (pos <= text.length()) {
            int lineEnd = text.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            if (startLine < 0) {
                if (start.region(pos, lineEnd).lookingAt()) {
                    startLine = pos;
                    startLineEnd = lineEnd;
                }
            } else if (end.region(pos, lineEnd).lookingAt()) {
                endOffset = pos;
                break;
            }
            pos = lineEnd + 1;
        }

        if (startLine < 0 || endOffset < 0) {
            throw new NoMarkersException();
        }
        String header = text.substring(0, startLine);
        String body = text.substring(startLineEnd + 1, endOffset);
        return new String[] {stripAscii(header), stripAscii(body)};
    }

    static String stripAscii(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && ASCII_WS.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && ASCII_WS.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }
}

package es.ulpgc.bigdata.stage1;

import java.util.Collection;
import java.util.Map;

/**
 * JSON output in the same format as Python's json.dump, so the three versions print the same thing.
 * line(): one line with ", " and ": " and ASCII only (json.dump defaults).
 * pretty(): 2-space indent and non-ASCII characters left unescaped (indent=2, ensure_ascii=False).
 */
final class Json {
    private Json() {
    }

    static String line(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, true);
        return sb.toString();
    }

    static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0, false);
        return sb.toString();
    }

    // level < 0: everything on one line
    private static void write(StringBuilder sb, Object value, int level, boolean ascii) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            quote(sb, (String) value, ascii);
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                separator(sb, level, first);
                first = false;
                quote(sb, String.valueOf(e.getKey()), ascii);
                sb.append(": ");
                write(sb, e.getValue(), level < 0 ? level : level + 1, ascii);
            }
            newline(sb, level);
            sb.append('}');
        } else if (value instanceof Collection) {
            Collection<?> list = (Collection<?>) value;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                separator(sb, level, first);
                first = false;
                write(sb, item, level < 0 ? level : level + 1, ascii);
            }
            newline(sb, level);
            sb.append(']');
        } else {
            sb.append(value);   // numbers and booleans
        }
    }

    private static void separator(StringBuilder sb, int level, boolean first) {
        if (level < 0) {
            if (!first) {
                sb.append(", ");
            }
            return;
        }
        if (!first) {
            sb.append(',');
        }
        newline(sb, level + 1);
    }

    private static void newline(StringBuilder sb, int level) {
        if (level >= 0) {
            sb.append('\n');
            for (int i = 0; i < level; i++) {
                sb.append("  ");
            }
        }
    }

    private static void quote(StringBuilder sb, String s, boolean ascii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || (ascii && c > 0x7E)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}

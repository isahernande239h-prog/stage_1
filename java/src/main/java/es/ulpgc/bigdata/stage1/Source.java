package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Lectura de libros: de gutenberg.org (o un espejo con las mismas URLs) o de una cache local. */
public interface Source {
    String DEFAULT_BASE = "https://www.gutenberg.org";

    String fetch(int bookId) throws IOException;

    static Source open(String spec) {
        if (spec.startsWith("cache:")) {
            return new CacheSource(Path.of(spec.substring("cache:".length())));
        }
        return new HttpSource(spec);
    }

    static String decode(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            // Java junta en un solo U+FFFD algunas secuencias mal formadas (por ejemplo ED A0 80)
            // y Python pone uno por cada trozo invalido: en ese caso se decodifica a mano
            text = decodeByHand(raw);
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text;
    }

    /** UTF-8 con U+FFFD por cada trozo maximo invalido (lo mismo que errors="replace" en Python). */
    static String decodeByHand(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length);
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                sb.append((char) c);
                i++;
                continue;
            }
            int need;
            int cp;
            int min = 0x80;
            int max = 0xBF;
            if (c >= 0xC2 && c <= 0xDF) {
                need = 1;
                cp = c & 0x1F;
            } else if (c >= 0xE0 && c <= 0xEF) {
                need = 2;
                cp = c & 0x0F;
                if (c == 0xE0) {
                    min = 0xA0;
                } else if (c == 0xED) {
                    max = 0x9F;   // fuera los surrogates
                }
            } else if (c >= 0xF0 && c <= 0xF4) {
                need = 3;
                cp = c & 0x07;
                if (c == 0xF0) {
                    min = 0x90;
                } else if (c == 0xF4) {
                    max = 0x8F;
                }
            } else {
                sb.append('\uFFFD');
                i++;
                continue;
            }
            int j = i + 1;
            boolean ok = true;
            for (int k = 0; k < need; k++, j++) {
                int d = j < b.length ? b[j] & 0xFF : -1;
                if (d < (k == 0 ? min : 0x80) || d > (k == 0 ? max : 0xBF)) {
                    ok = false;
                    break;
                }
                cp = (cp << 6) | (d & 0x3F);
            }
            if (ok) {
                sb.appendCodePoint(cp);
            } else {
                sb.append('\uFFFD');
            }
            i = j;
        }
        return sb.toString();
    }
}

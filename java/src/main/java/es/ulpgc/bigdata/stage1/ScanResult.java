package es.ulpgc.bigdata.stage1;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resultado de recorrer el datalake entero. */
public class ScanResult {
    public final Map<Integer, List<String>> complete = new LinkedHashMap<>();  // id -> particiones donde esta completo
    public final List<Path> orphans = new ArrayList<>();   // ficheros sin su pareja
    public final List<Path> temps = new ArrayList<>();     // .tmp que quedaron de una escritura cortada

    public Set<Integer> ids() {
        return new HashSet<>(complete.keySet());
    }
}

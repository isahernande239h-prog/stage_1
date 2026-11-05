package es.ulpgc.bigdata.stage1;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Result of walking the whole datalake. */
public class ScanResult {
    public final Map<Integer, List<String>> complete = new LinkedHashMap<>();  // id -> partitions where it is complete
    public final List<Path> orphans = new ArrayList<>();   // files missing their pair
    public final List<Path> temps = new ArrayList<>();     // .tmp files left over from a cut-off write

    public Set<Integer> ids() {
        return new HashSet<>(complete.keySet());
    }
}

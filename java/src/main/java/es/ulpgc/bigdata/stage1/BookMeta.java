package es.ulpgc.bigdata.stage1;

/** Book metadata taken from the header. Fields that are missing stay null. */
public record BookMeta(String title, String author, String language, Integer year, Integer releaseYear) {
}

package es.ulpgc.bigdata.stage1;

/** Metadatos de un libro sacados de la cabecera. Los que no aparecen quedan a null. */
public record BookMeta(String title, String author, String language, Integer year, Integer releaseYear) {
}

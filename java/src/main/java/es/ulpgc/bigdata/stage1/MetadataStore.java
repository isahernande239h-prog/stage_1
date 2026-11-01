package es.ulpgc.bigdata.stage1;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metadatos sacados de la cabecera y guardados en SQLite (datamarts/metadata.db). */
public class MetadataStore implements AutoCloseable {
    static final String[] COLUMNS = {"book_id", "title", "author", "language", "year", "release_year", "header_path", "body_path"};

    private final Connection conn;

    public MetadataStore(Path path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS books ("
                    + " book_id INTEGER PRIMARY KEY,"
                    + " title TEXT, author TEXT, language TEXT,"
                    + " year INTEGER, release_year INTEGER,"
                    + " header_path TEXT, body_path TEXT)");
        }
    }

    public void upsert(int bookId, BookMeta meta, String headerPath, String bodyPath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO books VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, bookId);
            ps.setString(2, meta.title());
            ps.setString(3, meta.author());
            ps.setString(4, meta.language());
            ps.setObject(5, meta.year());
            ps.setObject(6, meta.releaseYear());
            ps.setString(7, headerPath);
            ps.setString(8, bodyPath);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> get(int bookId) throws SQLException {
        List<Map<String, Object>> rows = query("SELECT * FROM books WHERE book_id = ?", bookId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> all() throws SQLException {
        return query("SELECT * FROM books ORDER BY book_id");
    }

    public List<Map<String, Object>> byAuthor(String text) throws SQLException {
        return query("SELECT * FROM books WHERE author LIKE ? ORDER BY book_id", "%" + text + "%");
    }

    private List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < COLUMNS.length; i++) {
                        row.put(COLUMNS[i], rs.getObject(i + 1));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}

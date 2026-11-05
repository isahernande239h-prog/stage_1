package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.sqlite.SQLiteJDBCLoader;

/** Table postings(term, book_id, tf) with key (term, book_id) in datamarts/inverted_index.db. */
public class SqliteStore implements IndexStore {
    static final int BATCH = 10000;

    private final Path path;
    private Connection conn;
    private PreparedStatement postingsQuery;

    public SqliteStore(Path datamarts) throws Exception {
        path = datamarts.resolve("inverted_index.db");
        // the native SQLite library takes ~0.3 s to load the first time; it's loaded here so it doesn't
        // count inside writeFull (in Python it loads on import sqlite3)
        SQLiteJDBCLoader.initialize();
    }

    private void connect() throws SQLException {
        if (conn == null) {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS postings ("
                        + " term TEXT NOT NULL, book_id INTEGER NOT NULL, tf INTEGER NOT NULL,"
                        + " PRIMARY KEY (term, book_id)) WITHOUT ROWID");
            }
        }
    }

    @Override
    public void writeFull(Map<String, Map<Integer, Integer>> index) throws SQLException {
        connect();
        List<String> terms = new ArrayList<>(index.keySet());
        terms.sort(IndexStore::compareCodePoints);
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO postings VALUES (?, ?, ?)")) {
            st.execute("DELETE FROM postings");
            // inserting in key order is much faster in the B-tree
            int pending = 0;
            for (String term : terms) {
                Map<Integer, Integer> found = index.get(term);
                List<Integer> ids = new ArrayList<>(found.keySet());
                Collections.sort(ids);
                for (int id : ids) {
                    ps.setString(1, term);
                    ps.setInt(2, id);
                    ps.setInt(3, found.get(id));
                    ps.addBatch();
                    if (++pending == BATCH) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public void open() throws SQLException {
        connect();
    }

    @Override
    public void addBook(int bookId, Map<String, Integer> tfs) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO postings VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Integer> e : tfs.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setInt(2, bookId);
                ps.setInt(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public Map<Integer, Integer> postings(String term) throws SQLException {
        if (postingsQuery == null) {
            postingsQuery = conn.prepareStatement("SELECT book_id, tf FROM postings WHERE term = ?");
        }
        Map<Integer, Integer> found = new HashMap<>();
        postingsQuery.setString(1, term);
        try (ResultSet rs = postingsQuery.executeQuery()) {
            while (rs.next()) {
                found.put(rs.getInt(1), rs.getInt(2));
            }
        }
        return found;
    }

    @Override
    public void terms(BiConsumer<String, Map<Integer, Integer>> action) throws SQLException {
        String current = null;
        Map<Integer, Integer> found = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT term, book_id, tf FROM postings ORDER BY term, book_id")) {
            while (rs.next()) {
                String term = rs.getString(1);
                if (!term.equals(current)) {
                    if (current != null) {
                        action.accept(current, found);
                    }
                    current = term;
                    found = new LinkedHashMap<>();
                }
                found.put(rs.getInt(2), rs.getInt(3));
            }
        }
        if (current != null) {
            action.accept(current, found);
        }
    }

    @Override
    public long diskUsage() throws IOException {
        return Files.size(path);
    }

    @Override
    public void close() throws SQLException {
        if (conn != null) {
            if (postingsQuery != null) {
                postingsQuery.close();
                postingsQuery = null;
            }
            conn.close();
            conn = null;
        }
    }
}

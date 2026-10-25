package edu.ucc.micro8.db;

import edu.ucc.micro8.lab.LabRepository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class DatabaseManager {

    private static final Path DEFAULT_DB_PATH = Path.of("micro8.db");

    private final Path dbPath;
    private final String jdbcUrl;

    public DatabaseManager(Path dbPath) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null").toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.dbPath;
    }

    public static DatabaseManager createDefault() {
        return new DatabaseManager(DEFAULT_DB_PATH);
    }

    public Path dbPath() {
        return dbPath;
    }

    public Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
        } catch (SQLException ex) {
            try {
                conn.close();
            } catch (SQLException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }
        return conn;
    }

    public void initialize() throws SQLException {
        try (Connection conn = openConnection()) {
            new IsaRepository(this).initializeSchemaAndSeed(conn);
            new LabRepository(this).seedLabsIfNeeded(conn);
        }
    }

    public String queryJournalMode(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    public String queryJournalMode() throws SQLException {
        try (Connection conn = openConnection()) {
            return queryJournalMode(conn);
        }
    }

    public int queryForeignKeys(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys;")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int queryForeignKeys() throws SQLException {
        try (Connection conn = openConnection()) {
            return queryForeignKeys(conn);
        }
    }
}

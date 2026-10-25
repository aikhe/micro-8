package edu.ucc.micro8.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void openConnectionEnablesWalModeAndForeignKeys() throws SQLException {
        Path dbFile = tempDir.resolve("micro8-test.db");
        DatabaseManager dbManager = new DatabaseManager(dbFile);

        try (Connection conn = dbManager.openConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase());
            }

            try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys;")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            assertEquals("wal", dbManager.queryJournalMode(conn).toLowerCase());
            assertEquals(1, dbManager.queryForeignKeys(conn));
        }
    }
}

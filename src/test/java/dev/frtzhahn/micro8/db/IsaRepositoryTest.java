package dev.frtzhahn.micro8.db;

import dev.frtzhahn.micro8.cpu.ControlSignals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static dev.frtzhahn.micro8.cpu.ControlSignals.AI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.AO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.BI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.BO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CE;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CLR;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.EO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.FI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.HLT;
import static dev.frtzhahn.micro8.cpu.ControlSignals.II;
import static dev.frtzhahn.micro8.cpu.ControlSignals.J;
import static dev.frtzhahn.micro8.cpu.ControlSignals.MI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.OI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.RI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.RO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.SU;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsaRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeAndCompileDefaultIsaProducesExpectedBitmasks() throws SQLException {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("isa-seed.db"));
        IsaRepository repo = new IsaRepository(dbManager);

        repo.initializeSchemaAndSeed();
        assertDoesNotThrow(() -> repo.validateNoBusContention());

        List<InstructionDef> instructions = repo.listInstructions();
        assertEquals(11, instructions.size());

        try (Connection conn = dbManager.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT setting_value FROM app_settings WHERE setting_key = 'hide_onboarding';")) {
            assertTrue(rs.next());
            assertEquals("0", rs.getString(1));
        }

        int[][][] store = repo.compileControlStore();
        assertEquals(256, store.length);
        assertEquals(6, store[0].length);
        assertEquals(4, store[0][0].length);

        for (int flag = 0; flag < 4; flag++) {
            assertEquals(CO | MI, store[0x00][0][flag]);
            assertEquals(RO | II | CE, store[0x00][1][flag]);
            assertEquals(CE | CLR, store[0x00][2][flag]);

            assertEquals(CO | MI, store[0x10][2][flag]);
            assertEquals(RO | MI | CE, store[0x10][3][flag]);
            assertEquals(RO | AI | CLR, store[0x10][4][flag]);
            assertEquals(0, store[0x10][5][flag]);

            assertEquals(CO | MI, store[0x11][2][flag]);
            assertEquals(RO | AI | CE | CLR, store[0x11][3][flag]);

            assertEquals(CO | MI, store[0x20][2][flag]);
            assertEquals(RO | MI | CE, store[0x20][3][flag]);
            assertEquals(RO | BI, store[0x20][4][flag]);
            assertEquals(EO | AI | FI | CLR, store[0x20][5][flag]);

            assertEquals(CO | MI, store[0x21][2][flag]);
            assertEquals(RO | MI | CE, store[0x21][3][flag]);
            assertEquals(RO | BI, store[0x21][4][flag]);
            assertEquals(EO | SU | AI | FI | CLR, store[0x21][5][flag]);

            assertEquals(CO | MI, store[0x30][2][flag]);
            assertEquals(RO | MI | CE, store[0x30][3][flag]);
            assertEquals(AO | RI | CLR, store[0x30][4][flag]);

            assertEquals(CO | MI, store[0x40][2][flag]);
            assertEquals(RO | J | CLR, store[0x40][3][flag]);

            assertEquals(AO | OI | CE | CLR, store[0xE0][2][flag]);
            assertEquals(CE | HLT | CLR, store[0xF0][2][flag]);

            assertEquals(CO | MI, store[0x99][0][flag]);
            assertEquals(RO | II | CE, store[0x99][1][flag]);
            assertEquals(CE | CLR, store[0x99][2][flag]);
        }

        assertEquals(CE | CLR, store[0x41][3][0]);
        assertEquals(RO | J | CLR, store[0x41][3][1]);
        assertEquals(CE | CLR, store[0x41][3][2]);
        assertEquals(RO | J | CLR, store[0x41][3][3]);

        assertEquals(CE | CLR, store[0x42][3][0]);
        assertEquals(CE | CLR, store[0x42][3][1]);
        assertEquals(RO | J | CLR, store[0x42][3][2]);
        assertEquals(RO | J | CLR, store[0x42][3][3]);
    }

    @Test
    void conflictingBusDriversThrowBusContentionException() throws SQLException {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("isa-contention.db"));
        IsaRepository repo = new IsaRepository(dbManager);
        repo.initializeSchemaAndSeed();

        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO microcode_step_signals (opcode, t_state, flag_state, signal_id) VALUES (?, ?, ?, ?)"
             )) {
            ps.setInt(1, 0x10);
            ps.setInt(2, 4);
            ps.setInt(3, 0);
            ps.setInt(4, ControlSignals.BIT_AO);
            ps.executeUpdate();
        }

        BusContentionException ex = assertThrows(BusContentionException.class, () -> repo.compileControlStore());
        assertEquals(0x10, ex.opcode());
        assertEquals("LDA", ex.mnemonic());
        assertEquals(4, ex.tState());
        assertEquals(0, ex.flagState());
        assertTrue(ex.conflictingDrivers().contains("RO"));
        assertTrue(ex.conflictingDrivers().contains("AO"));
    }

    @Test
    void controlSignalHelpersDetectBusDriversAndFormatActiveSignals() {
        assertTrue(ControlSignals.isBusDriver(RO));
        assertTrue(ControlSignals.isBusDriver(AO));
        assertTrue(ControlSignals.isBusDriver(BO));
        assertTrue(ControlSignals.isBusDriver(EO));
        assertTrue(ControlSignals.isBusDriver(CO));

        assertFalse(ControlSignals.isBusDriver(MI));
        assertFalse(ControlSignals.isBusDriver(AI));
        assertFalse(ControlSignals.isBusDriver(RO | AO));

        assertFalse(ControlSignals.hasBusContention(RO | AI | CLR));
        assertTrue(ControlSignals.hasBusContention(RO | AO | AI));

        assertEquals("NONE", ControlSignals.formatActiveSignals(0));
        assertEquals("RO | II | CE", ControlSignals.formatActiveSignals(RO | II | CE));
    }
}

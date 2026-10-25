package edu.ucc.micro8.db;

import edu.ucc.micro8.cpu.ControlSignals;
import edu.ucc.micro8.cpu.ControlSignals.SignalSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static edu.ucc.micro8.cpu.ControlSignals.AI;
import static edu.ucc.micro8.cpu.ControlSignals.AO;
import static edu.ucc.micro8.cpu.ControlSignals.BI;
import static edu.ucc.micro8.cpu.ControlSignals.CE;
import static edu.ucc.micro8.cpu.ControlSignals.CLR;
import static edu.ucc.micro8.cpu.ControlSignals.CO;
import static edu.ucc.micro8.cpu.ControlSignals.EO;
import static edu.ucc.micro8.cpu.ControlSignals.FI;
import static edu.ucc.micro8.cpu.ControlSignals.HLT;
import static edu.ucc.micro8.cpu.ControlSignals.II;
import static edu.ucc.micro8.cpu.ControlSignals.J;
import static edu.ucc.micro8.cpu.ControlSignals.MI;
import static edu.ucc.micro8.cpu.ControlSignals.OI;
import static edu.ucc.micro8.cpu.ControlSignals.RI;
import static edu.ucc.micro8.cpu.ControlSignals.RO;
import static edu.ucc.micro8.cpu.ControlSignals.SU;

public final class IsaRepository {

    private static final String SCHEMA_RESOURCE = "/edu/ucc/micro8/db/schema.sql";

    private static final String SQL_BUS_CONTENTION = """
            SELECT m.opcode,
                   COALESCE(i.mnemonic, 'UNKNOWN') AS mnemonic,
                   m.t_state,
                   m.flag_state,
                   GROUP_CONCAT(s.name, ',') AS drivers
            FROM microcode_step_signals m
            JOIN control_signals s ON s.signal_id = m.signal_id
            LEFT JOIN instructions i ON i.opcode = m.opcode
            WHERE s.is_bus_driver = 1
            GROUP BY m.opcode, m.t_state, m.flag_state
            HAVING COUNT(m.signal_id) > 1
            ORDER BY m.opcode, m.t_state, m.flag_state
            """;

    private final DatabaseManager dbManager;

    public IsaRepository(DatabaseManager dbManager) {
        this.dbManager = Objects.requireNonNull(dbManager, "dbManager must not be null");
    }

    public void initializeSchemaAndSeed() throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            initializeSchemaAndSeed(conn);
        }
    }

    public void initializeSchemaAndSeed(Connection conn) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            executeSchemaScript(conn);
            seedControlSignals(conn);
            if (countInstructions(conn) == 0) {
                seedDefaultIsa(conn);
            }
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    public void resetToDefaultIsa() throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM microcode_step_signals;");
                stmt.executeUpdate("DELETE FROM microcode_steps;");
                stmt.executeUpdate("DELETE FROM instructions;");
                seedControlSignals(conn);
                seedDefaultIsa(conn);
                validateNoBusContention(conn);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        }
    }

    public void saveInstructionMicrocode(InstructionDef def, int[][] tStateFlagControlWords) throws SQLException {
        Objects.requireNonNull(def, "def must not be null");
        if (def.mnemonic() == null || def.mnemonic().isBlank()) {
            throw new IllegalArgumentException("Instruction mnemonic must not be blank");
        }
        if (tStateFlagControlWords == null || tStateFlagControlWords.length != 6) {
            throw new IllegalArgumentException("tStateFlagControlWords must be int[6][4]");
        }
        for (int t = 0; t < 6; t++) {
            if (tStateFlagControlWords[t] == null || tStateFlagControlWords[t].length != 4) {
                throw new IllegalArgumentException("tStateFlagControlWords[" + t + "] must have length 4");
            }
        }

        int maskedOpcode = def.opcode() & 0xFF;
        String cleanMnemonic = def.mnemonic().trim().toUpperCase();
        String cleanDesc = def.description() == null ? "" : def.description().trim();

        try (Connection conn = dbManager.openConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                seedControlSignals(conn);

                String upsertInstrSql = """
                        INSERT INTO instructions (opcode, mnemonic, has_operand, is_builtin, description)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(opcode) DO UPDATE SET
                            mnemonic = excluded.mnemonic,
                            has_operand = excluded.has_operand,
                            is_builtin = excluded.is_builtin,
                            description = excluded.description
                        """;
                try (PreparedStatement ps = conn.prepareStatement(upsertInstrSql)) {
                    ps.setInt(1, maskedOpcode);
                    ps.setString(2, cleanMnemonic);
                    ps.setInt(3, def.hasOperand() ? 1 : 0);
                    ps.setInt(4, def.builtin() ? 1 : 0);
                    ps.setString(5, cleanDesc);
                    ps.executeUpdate();
                }

                try (PreparedStatement delSig = conn.prepareStatement("DELETE FROM microcode_step_signals WHERE opcode = ?;");
                     PreparedStatement delSteps = conn.prepareStatement("DELETE FROM microcode_steps WHERE opcode = ?;")) {
                    delSig.setInt(1, maskedOpcode);
                    delSig.executeUpdate();
                    delSteps.setInt(1, maskedOpcode);
                    delSteps.executeUpdate();
                }

                String stepSql = """
                        INSERT INTO microcode_steps (opcode, t_state, flag_state, control_word)
                        VALUES (?, ?, ?, ?)
                        """;
                String sigSql = """
                        INSERT INTO microcode_step_signals (opcode, t_state, flag_state, signal_id)
                        VALUES (?, ?, ?, ?)
                        """;
                try (PreparedStatement stepPs = conn.prepareStatement(stepSql);
                     PreparedStatement sigPs = conn.prepareStatement(sigSql)) {
                    for (int t = 0; t < 6; t++) {
                        for (int flag = 0; flag < 4; flag++) {
                            int word = tStateFlagControlWords[t][flag];
                            stepPs.setInt(1, maskedOpcode);
                            stepPs.setInt(2, t);
                            stepPs.setInt(3, flag);
                            stepPs.setInt(4, word);
                            stepPs.addBatch();

                            for (SignalSpec spec : ControlSignals.ALL_SIGNALS) {
                                if ((word & spec.mask()) != 0) {
                                    sigPs.setInt(1, maskedOpcode);
                                    sigPs.setInt(2, t);
                                    sigPs.setInt(3, flag);
                                    sigPs.setInt(4, spec.signalId());
                                    sigPs.addBatch();
                                }
                            }
                        }
                    }
                    stepPs.executeBatch();
                    sigPs.executeBatch();
                }

                validateNoBusContention(conn);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        }
    }

    public int[][] loadInstructionSteps(int opcode) throws SQLException {
        int maskedOpcode = opcode & 0xFF;
        int[][] steps = new int[6][4];
        int fetchT0 = CO | MI;
        int fetchT1 = RO | II | CE;
        int defaultT2 = CE | CLR;
        for (int flag = 0; flag < 4; flag++) {
            steps[0][flag] = fetchT0;
            steps[1][flag] = fetchT1;
            steps[2][flag] = defaultT2;
        }

        String sql = """
                SELECT t_state, flag_state, control_word
                FROM microcode_steps
                WHERE opcode = ?
                ORDER BY t_state ASC, flag_state ASC
                """;
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maskedOpcode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int tState = rs.getInt("t_state");
                    int flagState = rs.getInt("flag_state");
                    int controlWord = rs.getInt("control_word");
                    if (tState >= 0 && tState < 6 && flagState >= 0 && flagState < 4) {
                        steps[tState][flagState] = controlWord;
                    }
                }
            }
        }
        return steps;
    }

    public void validateNoBusContention() throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            validateNoBusContention(conn);
        }
    }

    public void validateNoBusContention(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL_BUS_CONTENTION)) {
            if (rs.next()) {
                int opcode = rs.getInt("opcode") & 0xFF;
                String mnemonic = rs.getString("mnemonic");
                int tState = rs.getInt("t_state");
                int flagState = rs.getInt("flag_state");
                String rawDrivers = rs.getString("drivers");
                List<String> drivers = rawDrivers == null || rawDrivers.isBlank()
                        ? List.of()
                        : Arrays.stream(rawDrivers.split(",")).map(String::trim).toList();
                throw new BusContentionException(opcode, mnemonic, tState, flagState, drivers);
            }
        }
    }

    public int[][][] compileControlStore() throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            return compileControlStore(conn);
        }
    }

    public int[][][] compileControlStore(Connection conn) throws SQLException {
        validateNoBusContention(conn);

        int[][][] store = new int[256][6][4];
        int fetchT0 = CO | MI;
        int fetchT1 = RO | II | CE;
        int defaultT2 = CE | CLR;

        for (int op = 0; op < 256; op++) {
            for (int flag = 0; flag < 4; flag++) {
                store[op][0][flag] = fetchT0;
                store[op][1][flag] = fetchT1;
                store[op][2][flag] = defaultT2;
            }
        }

        String sql = """
                SELECT m.opcode, m.t_state, m.flag_state, m.control_word, COALESCE(i.mnemonic, 'UNKNOWN') AS mnemonic
                FROM microcode_steps m
                LEFT JOIN instructions i ON i.opcode = m.opcode
                ORDER BY m.opcode, m.t_state, m.flag_state
                """;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int opcode = rs.getInt("opcode") & 0xFF;
                int tState = rs.getInt("t_state");
                int flagState = rs.getInt("flag_state");
                int controlWord = rs.getInt("control_word");
                if (ControlSignals.hasBusContention(controlWord)) {
                    String mnemonic = rs.getString("mnemonic");
                    List<String> drivers = extractDriverNames(controlWord);
                    throw new BusContentionException(opcode, mnemonic, tState, flagState, drivers);
                }
                if (tState >= 0 && tState < 6 && flagState >= 0 && flagState < 4) {
                    store[opcode][tState][flagState] = controlWord;
                }
            }
        }
        return store;
    }

    public List<InstructionDef> listInstructions() throws SQLException {
        List<InstructionDef> list = new ArrayList<>();
        String sql = """
                SELECT opcode, mnemonic, has_operand, is_builtin, description
                FROM instructions
                ORDER BY opcode ASC
                """;
        try (Connection conn = dbManager.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new InstructionDef(
                        rs.getInt("opcode") & 0xFF,
                        rs.getString("mnemonic"),
                        rs.getInt("has_operand") != 0,
                        rs.getInt("is_builtin") != 0,
                        rs.getString("description")
                ));
            }
        }
        return list;
    }

    private void executeSchemaScript(Connection conn) throws SQLException {
        String script = loadSchemaSql();
        try (Statement stmt = conn.createStatement()) {
            for (String rawStatement : script.split(";")) {
                String sql = rawStatement.trim();
                if (!sql.isEmpty()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    private String loadSchemaSql() throws SQLException {
        try (InputStream in = IsaRepository.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new SQLException("Missing SQLite schema resource: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new SQLException("Failed to read SQLite schema resource", ex);
        }
    }

    private void seedControlSignals(Connection conn) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO control_signals (signal_id, name, bit_position, is_bus_driver, description)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (SignalSpec spec : ControlSignals.ALL_SIGNALS) {
                ps.setInt(1, spec.signalId());
                ps.setString(2, spec.name());
                ps.setInt(3, spec.bitPosition());
                ps.setInt(4, spec.busDriver() ? 1 : 0);
                ps.setString(5, spec.description());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int countInstructions(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM instructions;")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void seedDefaultIsa(Connection conn) throws SQLException {
        int fetchT0 = CO | MI;
        int fetchT1 = RO | II | CE;

        insertUniformInstruction(conn, 0x00, "NOP", false, "No operation (advance PC past padding byte)",
                new int[]{fetchT0, fetchT1, CE | CLR, 0, 0, 0});

        insertUniformInstruction(conn, 0x10, "LDA", true, "Load RAM byte at operand address into Register A",
                new int[]{fetchT0, fetchT1, CO | MI, RO | MI | CE, RO | AI | CLR, 0});

        insertUniformInstruction(conn, 0x11, "LDI", true, "Load 8-bit immediate operand into Register A",
                new int[]{fetchT0, fetchT1, CO | MI, RO | AI | CE | CLR, 0, 0});

        insertUniformInstruction(conn, 0x20, "ADD", true, "Add RAM byte at operand address to Register A (updates Z, C)",
                new int[]{fetchT0, fetchT1, CO | MI, RO | MI | CE, RO | BI, EO | AI | FI | CLR});

        insertUniformInstruction(conn, 0x21, "SUB", true, "Subtract RAM byte at operand address from Register A (updates Z, C)",
                new int[]{fetchT0, fetchT1, CO | MI, RO | MI | CE, RO | BI, EO | SU | AI | FI | CLR});

        insertUniformInstruction(conn, 0x30, "STA", true, "Store Register A into RAM at operand address",
                new int[]{fetchT0, fetchT1, CO | MI, RO | MI | CE, AO | RI | CLR, 0});

        insertUniformInstruction(conn, 0x40, "JMP", true, "Unconditional jump to operand address",
                new int[]{fetchT0, fetchT1, CO | MI, RO | J | CLR, 0, 0});

        int[][] jzMatrix = new int[4][6];
        for (int flag = 0; flag < 4; flag++) {
            boolean zeroSet = (flag & 1) != 0;
            jzMatrix[flag][0] = fetchT0;
            jzMatrix[flag][1] = fetchT1;
            jzMatrix[flag][2] = CO | MI;
            jzMatrix[flag][3] = zeroSet ? (RO | J | CLR) : (CE | CLR);
        }
        insertFlagConditionedInstruction(conn, 0x41, "JZ", true, "Jump to operand address if Zero flag is set (Z=1)", jzMatrix);

        int[][] jcMatrix = new int[4][6];
        for (int flag = 0; flag < 4; flag++) {
            boolean carrySet = (flag & 2) != 0;
            jcMatrix[flag][0] = fetchT0;
            jcMatrix[flag][1] = fetchT1;
            jcMatrix[flag][2] = CO | MI;
            jcMatrix[flag][3] = carrySet ? (RO | J | CLR) : (CE | CLR);
        }
        insertFlagConditionedInstruction(conn, 0x42, "JC", true, "Jump to operand address if Carry flag is set (C=1)", jcMatrix);

        insertUniformInstruction(conn, 0xE0, "OUT", false, "Latch Register A into Output Register",
                new int[]{fetchT0, fetchT1, AO | OI | CE | CLR, 0, 0, 0});

        insertUniformInstruction(conn, 0xF0, "HLT", false, "Halt system clock after advancing PC past padding byte",
                new int[]{fetchT0, fetchT1, CE | HLT | CLR, 0, 0, 0});
    }

    private void insertUniformInstruction(
            Connection conn,
            int opcode,
            String mnemonic,
            boolean hasOperand,
            String description,
            int[] stepsByTState
    ) throws SQLException {
        int[][] matrix = new int[4][6];
        for (int flag = 0; flag < 4; flag++) {
            System.arraycopy(stepsByTState, 0, matrix[flag], 0, 6);
        }
        insertFlagConditionedInstruction(conn, opcode, mnemonic, hasOperand, description, matrix);
    }

    private void insertFlagConditionedInstruction(
            Connection conn,
            int opcode,
            String mnemonic,
            boolean hasOperand,
            String description,
            int[][] flagTStateMatrix
    ) throws SQLException {
        int maskedOpcode = opcode & 0xFF;

        String instrSql = """
                INSERT INTO instructions (opcode, mnemonic, has_operand, is_builtin, description)
                VALUES (?, ?, ?, 1, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(instrSql)) {
            ps.setInt(1, maskedOpcode);
            ps.setString(2, mnemonic);
            ps.setInt(3, hasOperand ? 1 : 0);
            ps.setString(4, description);
            ps.executeUpdate();
        }

        String stepSql = """
                INSERT INTO microcode_steps (opcode, t_state, flag_state, control_word)
                VALUES (?, ?, ?, ?)
                """;
        String sigSql = """
                INSERT INTO microcode_step_signals (opcode, t_state, flag_state, signal_id)
                VALUES (?, ?, ?, ?)
                """;

        try (PreparedStatement stepPs = conn.prepareStatement(stepSql);
             PreparedStatement sigPs = conn.prepareStatement(sigSql)) {
            for (int flag = 0; flag < 4; flag++) {
                for (int t = 0; t < 6; t++) {
                    int word = flagTStateMatrix[flag][t];
                    stepPs.setInt(1, maskedOpcode);
                    stepPs.setInt(2, t);
                    stepPs.setInt(3, flag);
                    stepPs.setInt(4, word);
                    stepPs.addBatch();

                    for (SignalSpec spec : ControlSignals.ALL_SIGNALS) {
                        if ((word & spec.mask()) != 0) {
                            sigPs.setInt(1, maskedOpcode);
                            sigPs.setInt(2, t);
                            sigPs.setInt(3, flag);
                            sigPs.setInt(4, spec.signalId());
                            sigPs.addBatch();
                        }
                    }
                }
            }
            stepPs.executeBatch();
            sigPs.executeBatch();
        }
    }

    private List<String> extractDriverNames(int controlWord) {
        List<String> drivers = new ArrayList<>();
        for (SignalSpec spec : ControlSignals.ALL_SIGNALS) {
            if (spec.busDriver() && (controlWord & spec.mask()) != 0) {
                drivers.add(spec.name());
            }
        }
        return drivers;
    }
}

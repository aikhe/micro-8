package edu.ucc.micro8.lab;

import edu.ucc.micro8.db.DatabaseManager;
import edu.ucc.micro8.db.IsaRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite persistence repository for Clean-Slate Lab Challenges and deterministic test cases.
 */
public final class LabRepository {

    private final DatabaseManager dbManager;

    public LabRepository(DatabaseManager dbManager) {
        this.dbManager = Objects.requireNonNull(dbManager, "dbManager must not be null");
    }

    public void initializeSchemaAndSeed() throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            new IsaRepository(dbManager).initializeSchemaAndSeed(conn);
            seedLabsIfNeeded(conn);
        }
    }

    public void seedLabsIfNeeded(Connection conn) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (countLabs(conn) == 0) {
                seedDefaultLabs(conn);
            }
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    public List<LabChallenge> listLabs() throws SQLException {
        List<LabChallenge> labs = new ArrayList<>();
        String sql = """
                SELECT lab_id, title, description, starter_code, max_cycles, passed, passed_cases, total_cases
                FROM lab_challenges
                ORDER BY lab_id ASC
                """;
        try (Connection conn = dbManager.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int labId = rs.getInt("lab_id");
                List<LabTestCase> cases = loadTestCases(conn, labId);
                labs.add(new LabChallenge(
                        labId,
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("starter_code"),
                        rs.getInt("max_cycles"),
                        rs.getInt("passed") != 0,
                        rs.getInt("passed_cases"),
                        rs.getInt("total_cases"),
                        cases
                ));
            }
        }
        return labs;
    }

    public Optional<LabChallenge> findById(int labId) throws SQLException {
        String sql = """
                SELECT lab_id, title, description, starter_code, max_cycles, passed, passed_cases, total_cases
                FROM lab_challenges
                WHERE lab_id = ?
                """;
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, labId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<LabTestCase> cases = loadTestCases(conn, labId);
                    return Optional.of(new LabChallenge(
                            rs.getInt("lab_id"),
                            rs.getString("title"),
                            rs.getString("description"),
                            rs.getString("starter_code"),
                            rs.getInt("max_cycles"),
                            rs.getInt("passed") != 0,
                            rs.getInt("passed_cases"),
                            rs.getInt("total_cases"),
                            cases
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public void updateLabProgress(int labId, boolean passed, int passedCases, int totalCases) throws SQLException {
        String sql = """
                UPDATE lab_challenges
                SET passed = ?, passed_cases = ?, total_cases = ?
                WHERE lab_id = ?
                """;
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, passed ? 1 : 0);
            ps.setInt(2, Math.max(0, passedCases));
            ps.setInt(3, Math.max(0, totalCases));
            ps.setInt(4, labId);
            ps.executeUpdate();
        }
    }

    private List<LabTestCase> loadTestCases(Connection conn, int labId) throws SQLException {
        List<LabTestCase> cases = new ArrayList<>();
        String sql = """
                SELECT case_id, lab_id, case_order, description,
                       input_ram_spec, expected_out_reg, expected_reg_a, expected_ram_spec
                FROM lab_test_cases
                WHERE lab_id = ?
                ORDER BY case_order ASC
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, labId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cases.add(new LabTestCase(
                            rs.getInt("case_id"),
                            rs.getInt("lab_id"),
                            rs.getInt("case_order"),
                            rs.getString("description"),
                            LabTestCase.parseRamSpec(rs.getString("input_ram_spec")),
                            rs.getInt("expected_out_reg"),
                            rs.getInt("expected_reg_a"),
                            LabTestCase.parseRamSpec(rs.getString("expected_ram_spec"))
                    ));
                }
            }
        }
        return cases;
    }

    private int countLabs(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM lab_challenges;")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void seedDefaultLabs(Connection conn) throws SQLException {
        insertLab(
                conn,
                1,
                "Lab #1: Unsigned 8-Bit Addition & Output (LDA + ADD + OUT)",
                "Load unsigned bytes at RAM[0xF0] and RAM[0xF1], add them in the ALU, latch the sum into OUT, and HLT.",
                """
                        ; Lab #1: Add RAM[0xF0] and RAM[0xF1], output the sum to OUT, and HLT
                        START:
                            LDA 0xF0
                            ; TODO: Add RAM[0xF1], output to OUT, and HLT
                            HLT
                        """,
                2000,
                List.of(
                        new SeedCase(1, "Add 0x15 (21) + 0x27 (39) -> 0x3C (60)", "0xF0=0x15,0xF1=0x27", 0x3C, 0x3C, ""),
                        new SeedCase(2, "Add 0x80 (128) + 0x20 (32) -> 0xA0 (160)", "0xF0=0x80,0xF1=0x20", 0xA0, 0xA0, ""),
                        new SeedCase(3, "Add 0x00 (0) + 0xFF (255) -> 0xFF (255)", "0xF0=0x00,0xF1=0xFF", 0xFF, 0xFF, "")
                )
        );

        insertLab(
                conn,
                2,
                "Lab #2: Two's Complement Subtraction & Memory Store (SUB + STA)",
                "Compute RAM[0xF0] - RAM[0xF1], store the difference into RAM[0xF2], latch the difference into OUT, and HLT.",
                """
                        ; Lab #2: Compute RAM[0xF0] - RAM[0xF1], store at RAM[0xF2], output to OUT, and HLT
                        START:
                            LDA 0xF0
                            ; TODO: Subtract RAM[0xF1], store at 0xF2, output to OUT, and HLT
                            HLT
                        """,
                2000,
                List.of(
                        new SeedCase(1, "Subtract 0x50 (80) - 0x20 (32) -> 0x30 (48)", "0xF0=0x50,0xF1=0x20", 0x30, 0x30, "0xF2=0x30"),
                        new SeedCase(2, "Subtract 0xFE (254) - 0x7F (127) -> 0x7F (127)", "0xF0=0xFE,0xF1=0x7F", 0x7F, 0x7F, "0xF2=0x7F"),
                        new SeedCase(3, "Subtract 0x2A (42) - 0x2A (42) -> 0x00 (0)", "0xF0=0x2A,0xF1=0x2A", 0x00, 0x00, "0xF2=0x00")
                )
        );

        insertLab(
                conn,
                3,
                "Lab #3: Conditional Branching - Unsigned Maximum (SUB + JC)",
                "Compare unsigned bytes at RAM[0xF0] and RAM[0xF1] using SUB and JC (Carry=1 when A >= B), store the larger unsigned byte at RAM[0xF2], output to OUT, and HLT.",
                """
                        ; Lab #3: Find unsigned max(RAM[0xF0], RAM[0xF1]), store at RAM[0xF2], output to OUT, and HLT
                        START:
                            LDA 0xF0
                            SUB 0xF1
                            ; Hint: In 2's complement subtraction A + (~B) + 1, Carry=1 means A >= B (no borrow)
                            HLT
                        """,
                3000,
                List.of(
                        new SeedCase(1, "Max(0xFE [254], 0x10 [16]) -> 0xFE (254)", "0xF0=0xFE,0xF1=0x10", 0xFE, 0xFE, "0xF2=0xFE"),
                        new SeedCase(2, "Max(0x12 [18], 0x85 [133]) -> 0x85 (133)", "0xF0=0x12,0xF1=0x85", 0x85, 0x85, "0xF2=0x85"),
                        new SeedCase(3, "Max(0x40 [64], 0x40 [64]) -> 0x40 (64)", "0xF0=0x40,0xF1=0x40", 0x40, 0x40, "0xF2=0x40")
                )
        );

        insertLab(
                conn,
                4,
                "Lab #4: Iterative Multiplication via Loop & JZ",
                "Multiply unsigned RAM[0xF0] by RAM[0xF1] using a repeated-addition loop and JZ, store the product at RAM[0xF2], output to OUT, and HLT.",
                """
                        ; Lab #4: Multiply RAM[0xF0] * RAM[0xF1], store product at RAM[0xF2], output to OUT, and HLT
                        START:
                            LDI 0
                            STA 0xF2
                            ; TODO: Loop RAM[0xF1] times adding RAM[0xF0] into RAM[0xF2]
                            HLT
                        """,
                5000,
                List.of(
                        new SeedCase(1, "Multiply 6 * 7 -> 42 (0x2A)", "0xF0=0x06,0xF1=0x07", 0x2A, 0x2A, "0xF2=0x2A"),
                        new SeedCase(2, "Multiply 15 * 0 -> 0 (0x00)", "0xF0=0x0F,0xF1=0x00", 0x00, 0x00, "0xF2=0x00"),
                        new SeedCase(3, "Multiply 12 * 11 -> 132 (0x84)", "0xF0=0x0C,0xF1=0x0B", 0x84, 0x84, "0xF2=0x84")
                )
        );
    }

    private record SeedCase(
            int caseOrder,
            String description,
            String inputRamSpec,
            int expectedOutReg,
            int expectedRegA,
            String expectedRamSpec
    ) {
    }

    private void insertLab(
            Connection conn,
            int labId,
            String title,
            String description,
            String starterCode,
            int maxCycles,
            List<SeedCase> cases
    ) throws SQLException {
        String labSql = """
                INSERT INTO lab_challenges (lab_id, title, description, starter_code, max_cycles, passed, passed_cases, total_cases)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(labSql)) {
            ps.setInt(1, labId);
            ps.setString(2, title);
            ps.setString(3, description);
            ps.setString(4, starterCode);
            ps.setInt(5, maxCycles);
            ps.setInt(6, cases.size());
            ps.executeUpdate();
        }

        String caseSql = """
                INSERT INTO lab_test_cases (
                    lab_id, case_order, description,
                    input_ram_spec, expected_out_reg, expected_reg_a, expected_ram_spec
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(caseSql)) {
            for (SeedCase sc : cases) {
                ps.setInt(1, labId);
                ps.setInt(2, sc.caseOrder());
                ps.setString(3, sc.description());
                ps.setString(4, sc.inputRamSpec());
                ps.setInt(5, sc.expectedOutReg());
                ps.setInt(6, sc.expectedRegA());
                ps.setString(7, sc.expectedRamSpec());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}

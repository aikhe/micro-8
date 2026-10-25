package dev.frtzhahn.micro8;

import dev.frtzhahn.micro8.asm.AssembledProgram;
import dev.frtzhahn.micro8.asm.Assembler;
import dev.frtzhahn.micro8.cpu.ControlSignals;
import dev.frtzhahn.micro8.cpu.CpuCore;
import dev.frtzhahn.micro8.db.BusContentionException;
import dev.frtzhahn.micro8.db.DatabaseManager;
import dev.frtzhahn.micro8.db.InstructionDef;
import dev.frtzhahn.micro8.db.IsaRepository;
import dev.frtzhahn.micro8.db.SnapshotRepository;
import dev.frtzhahn.micro8.db.WorkspaceSnapshot;
import dev.frtzhahn.micro8.lab.LabChallenge;
import dev.frtzhahn.micro8.lab.LabGradeReport;
import dev.frtzhahn.micro8.lab.LabGrader;
import dev.frtzhahn.micro8.lab.LabRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Micro8SystemIntegrationTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private IsaRepository isaRepo;
    private SnapshotRepository snapshotRepo;
    private LabRepository labRepo;
    private LabGrader labGrader;
    private CpuCore cpuCore;
    private Assembler assembler;

    @BeforeEach
    void setUp() throws SQLException {
        dbManager = new DatabaseManager(tempDir.resolve("integration-system.db"));
        dbManager.initialize();

        isaRepo = new IsaRepository(dbManager);
        isaRepo.initializeSchemaAndSeed();

        snapshotRepo = new SnapshotRepository(dbManager);
        labRepo = new LabRepository(dbManager);

        int[][][] controlStore = isaRepo.compileControlStore();
        cpuCore = new CpuCore();
        cpuCore.setControlStore(controlStore);

        assembler = new Assembler();
        assembler.updateInstructions(isaRepo.listInstructions());
        labGrader = new LabGrader(isaRepo, labRepo);
    }

    @Test
    @DisplayName("End-to-end program assembly, execution, and DeltaJournal macro undo")
    void testAssembleExecuteAndUndo() {
        String source = """
                ; Compute (5 + 7) = 12 and output
                LDA NUM1
                ADD NUM2
                OUT
                HLT
                NUM1: DB 0x05
                NUM2: DB 0x07
                """;

        AssembledProgram program = assembler.assemble(source);
        assertTrue(program.isSuccess(), "Assembly should succeed without errors");

        byte[] ramImage = program.ramImage();
        for (int i = 0; i < 256; i++) {
            cpuCore.writeRam(i, Byte.toUnsignedInt(ramImage[i]));
        }

        assertEquals(0, cpuCore.getPcUnsigned());
        assertEquals(0, cpuCore.getRegAUnsigned());
        assertEquals(0, cpuCore.getOutRegUnsigned());

        // Step 1: LDA NUM1 (PC 0 -> 2)
        boolean hasMore = cpuCore.stepMacro();
        assertTrue(hasMore);
        assertEquals(0x05, cpuCore.getRegAUnsigned());
        assertEquals(2, cpuCore.getPcUnsigned());

        // Step 2: ADD NUM2 (PC 2 -> 4)
        hasMore = cpuCore.stepMacro();
        assertTrue(hasMore);
        assertEquals(0x0C, cpuCore.getRegAUnsigned());
        assertEquals(4, cpuCore.getPcUnsigned());

        // Step 3: OUT (PC 4 -> 6)
        hasMore = cpuCore.stepMacro();
        assertTrue(hasMore);
        assertEquals(0x0C, cpuCore.getOutRegUnsigned());
        assertEquals(6, cpuCore.getPcUnsigned());

        // Step 4: HLT (PC 6 -> 8)
        cpuCore.stepMacro();
        assertTrue(cpuCore.isHalted(), "CPU should be halted after HLT instruction");
        assertFalse(cpuCore.stepMacro(), "Subsequent stepMacro when halted should return false");

        // Verify DeltaJournal reverse step: undo HLT
        int undone = cpuCore.undoMacroStep();
        assertTrue(undone > 0);
        assertFalse(cpuCore.isHalted());
        assertEquals(6, cpuCore.getPcUnsigned());

        // Undo OUT
        undone = cpuCore.undoMacroStep();
        assertTrue(undone > 0);
        assertEquals(0x00, cpuCore.getOutRegUnsigned());
        assertEquals(4, cpuCore.getPcUnsigned());

        // Undo ADD
        undone = cpuCore.undoMacroStep();
        assertTrue(undone > 0);
        assertEquals(0x05, cpuCore.getRegAUnsigned());
        assertEquals(2, cpuCore.getPcUnsigned());

        // Undo LDA back to initial state
        undone = cpuCore.undoMacroStep();
        assertTrue(undone > 0);
        assertEquals(0x00, cpuCore.getRegAUnsigned());
        assertEquals(0, cpuCore.getPcUnsigned());
    }

    @Test
    @DisplayName("End-to-end workspace snapshot capture, save, and restore via SQLite")
    void testWorkspaceSnapshotSaveAndRestore() throws SQLException {
        cpuCore.writeRam(0x00, 0x20); // ADD
        cpuCore.writeRam(0x01, 0x42);
        cpuCore.writeRam(0x42, 0x1A);
        cpuCore.setRegA(0x1A);
        cpuCore.setRegB(0x05);
        cpuCore.setPc(0x02);
        cpuCore.setMar(0x42);
        cpuCore.setIr(0x20);
        cpuCore.setOutReg(0x1F);
        cpuCore.setCycleCount(105);

        String source = "LDA 0x42\nHLT\n";
        WorkspaceSnapshot captured = snapshotRepo.captureFromCpu("integration-snap", source, cpuCore);
        snapshotRepo.saveSnapshot(captured, false);

        List<WorkspaceSnapshot> list = snapshotRepo.listSnapshots();
        assertEquals(1, list.size());

        WorkspaceSnapshot loaded = list.getFirst();
        assertEquals("integration-snap", loaded.name());
        assertEquals(source, loaded.assemblySource());
        assertEquals(0x1A, loaded.regA());
        assertEquals(0x42, loaded.mar());
        assertEquals(105, loaded.cycleCount());
        assertArrayEquals(cpuCore.copyRam(), loaded.ramBlob());

        CpuCore restoredCpu = new CpuCore();
        snapshotRepo.restoreIntoCpu(loaded, restoredCpu);

        assertEquals(0x1A, restoredCpu.getRegAUnsigned());
        assertEquals(0x05, restoredCpu.getRegBUnsigned());
        assertEquals(0x02, restoredCpu.getPcUnsigned());
        assertEquals(0x42, restoredCpu.getMarUnsigned());
        assertEquals(0x20, restoredCpu.getIrUnsigned());
        assertEquals(0x1F, restoredCpu.getOutRegUnsigned());
        assertEquals(105, restoredCpu.getCycleCount());
        assertArrayEquals(cpuCore.copyRam(), restoredCpu.copyRam());
    }

    @Test
    @DisplayName("End-to-end headless grading of Lab 1 with reference solution")
    void testLabChallengeGrader() throws SQLException {
        List<LabChallenge> challenges = labRepo.listLabs();
        assertFalse(challenges.isEmpty());
        LabChallenge lab1 = challenges.get(0);

        String referenceSolution = """
                START:
                    LDA 0xF0
                    ADD 0xF1
                    OUT
                    HLT
                """;

        LabGradeReport report = labGrader.grade(lab1, referenceSolution, cpuCore);
        assertNotNull(report);
        assertTrue(report.allPassed(), "Lab 1 reference solution must pass all test cases");
        assertEquals(3, report.totalCases());
        assertEquals(3, report.passedCases());
        assertTrue(report.summaryMessage().contains("PASSED"));
    }

    @Test
    @DisplayName("Bus contention check rejects simultaneous conflicting bus drivers")
    void testBusContentionValidator() throws SQLException {
        // Direct validation: bitmask with both RO and AO active
        int conflictingSignals = ControlSignals.RO | ControlSignals.AO;
        int driversCount = Integer.bitCount(conflictingSignals & ControlSignals.BUS_DRIVER_MASK);
        assertTrue(driversCount > 1, "RO and AO are conflicting bus drivers");

        // DB validation: inject conflicting microcode step and assert BusContentionException
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

        assertThrows(BusContentionException.class, () -> {
            isaRepo.validateNoBusContention();
        });
    }
}

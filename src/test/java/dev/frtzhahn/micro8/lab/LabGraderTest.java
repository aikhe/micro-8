package dev.frtzhahn.micro8.lab;

import dev.frtzhahn.micro8.cpu.CpuCore;
import dev.frtzhahn.micro8.db.DatabaseManager;
import dev.frtzhahn.micro8.db.IsaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabGraderTest {

    @Test
    void wipesDirtyCpuToCleanSlate() {
        CpuCore cpu = new CpuCore();
        cpu.writeRam(0x00, 0x11);
        cpu.writeRam(0x01, 0xFF);
        cpu.writeRam(0xF0, 0xAA);
        cpu.setRegA(0xFF);
        cpu.setRegB(0xFE);
        cpu.setOutReg(0x80);
        cpu.setZeroFlag(true);
        cpu.setCarryFlag(true);
        cpu.stepMicro();

        assertFalse(cpu.getJournal().isEmpty());
        assertTrue(cpu.getCycleCount() > 0);

        LabGrader.wipeToCleanSlate(cpu);

        for (int addr = 0; addr < 256; addr++) {
            assertEquals(0, cpu.readRamUnsigned(addr));
        }
        assertEquals(0, cpu.getRegAUnsigned());
        assertEquals(0, cpu.getRegBUnsigned());
        assertEquals(0, cpu.getPcUnsigned());
        assertEquals(0, cpu.getMarUnsigned());
        assertEquals(0, cpu.getIrUnsigned());
        assertEquals(0, cpu.getOutRegUnsigned());
        assertFalse(cpu.isZeroFlag());
        assertFalse(cpu.isCarryFlag());
        assertFalse(cpu.isHalted());
        assertEquals(0, cpu.getTState());
        assertEquals(0, cpu.getCycleCount());
        assertTrue(cpu.getJournal().isEmpty());
        assertTrue(cpu.getTraceQueue().isEmpty());
    }

    @Test
    void gradesAllFourLabsDeterministicallyAndDetectsInfiniteLoopTimeout(@TempDir Path tempDir) throws SQLException {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("micro8-m9.db"));
        dbManager.initialize();

        IsaRepository isaRepo = new IsaRepository(dbManager);
        LabRepository labRepo = new LabRepository(dbManager);
        LabGrader grader = new LabGrader(isaRepo, labRepo);

        List<LabChallenge> labs = labRepo.listLabs();
        assertEquals(4, labs.size());
        for (LabChallenge lab : labs) {
            assertEquals(3, lab.testCases().size());
            assertFalse(lab.passed());
        }

        CpuCore cpu = new CpuCore();

        LabGradeReport report1 = grader.grade(labs.get(0), """
                START:
                    LDA 0xF0
                    ADD 0xF1
                    OUT
                    HLT
                """, cpu);
        assertTrue(report1.allPassed());
        assertEquals(3, report1.passedCases());

        LabGradeReport report2 = grader.grade(labs.get(1), """
                START:
                    LDA 0xF0
                    SUB 0xF1
                    STA 0xF2
                    OUT
                    HLT
                """, cpu);
        assertTrue(report2.allPassed());
        assertEquals(3, report2.passedCases());

        LabGradeReport report3 = grader.grade(labs.get(2), """
                START:
                    LDA 0xF0
                    SUB 0xF1
                    JC FIRST_GE
                    LDA 0xF1
                    JMP DONE
                FIRST_GE:
                    LDA 0xF0
                DONE:
                    STA 0xF2
                    OUT
                    HLT
                """, cpu);
        assertTrue(report3.allPassed());
        assertEquals(3, report3.passedCases());

        LabGradeReport report4 = grader.grade(labs.get(3), """
                START:
                    LDI 0
                    STA 0xF2
                LOOP:
                    LDA 0xF1
                    ADD ZERO
                    JZ DONE
                    SUB ONE
                    STA 0xF1
                    LDA 0xF2
                    ADD 0xF0
                    STA 0xF2
                    JMP LOOP
                DONE:
                    LDA 0xF2
                    OUT
                    HLT

                    ORG 0xE0
                ZERO:
                    DB 0
                ONE:
                    DB 1
                """, cpu);
        assertTrue(report4.allPassed(), () -> "Lab 4 diagnostic: " + report4.caseResults());
        assertEquals(3, report4.passedCases());

        for (LabChallenge updated : labRepo.listLabs()) {
            assertTrue(updated.passed());
            assertEquals(3, updated.passedCases());
        }

        LabGradeReport timeoutReport = grader.grade(labs.get(0), """
                LOOP:
                    JMP LOOP
                """, cpu);
        assertFalse(timeoutReport.allPassed());
        assertEquals(0, timeoutReport.passedCases());
        assertTrue(timeoutReport.caseResults().getFirst().diagnostic().contains("Timeout"));
    }
}

package dev.frtzhahn.micro8.db;

import dev.frtzhahn.micro8.asm.AssembledProgram;
import dev.frtzhahn.micro8.asm.Assembler;
import dev.frtzhahn.micro8.cpu.CpuCore;
import dev.frtzhahn.micro8.ui.DatasheetGenerator;
import dev.frtzhahn.micro8.ui.MicrocodeStepRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static dev.frtzhahn.micro8.cpu.ControlSignals.AI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.BI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CE;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CLR;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.EO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.FI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.II;
import static dev.frtzhahn.micro8.cpu.ControlSignals.MI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.RO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrocodeDesignerTest {

    @Test
    void savesCustomInstructionAssemblesExecutesAndRendersInDatasheet(@TempDir Path tempDir) throws SQLException {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("micro8-m7.db"));
        IsaRepository repo = new IsaRepository(dbManager);
        repo.initializeSchemaAndSeed();

        int[][] adiSteps = new int[6][4];
        for (int flag = 0; flag < 4; flag++) {
            adiSteps[0][flag] = CO | MI;
            adiSteps[1][flag] = RO | II | CE;
            adiSteps[2][flag] = CO | MI;
            adiSteps[3][flag] = RO | BI | CE;
            adiSteps[4][flag] = EO | AI | FI | CLR;
            adiSteps[5][flag] = 0;
        }

        InstructionDef adiDef = new InstructionDef(
                0x60,
                "ADI",
                true,
                false,
                "Add 8-bit immediate operand to Register A"
        );
        repo.saveInstructionMicrocode(adiDef, adiSteps);

        List<InstructionDef> instructions = repo.listInstructions();
        assertEquals(12, instructions.size());

        int[][] loadedSteps = repo.loadInstructionSteps(0x60);
        assertEquals(RO | BI | CE, loadedSteps[3][0]);
        assertEquals(EO | AI | FI | CLR, loadedSteps[4][3]);

        int[][][] compiledStore = repo.compileControlStore();
        Assembler assembler = new Assembler(instructions);
        AssembledProgram program = assembler.assemble("""
                LDI 15
                ADI 27
                OUT
                HLT
                """);
        assertTrue(program.isSuccess());

        CpuCore cpu = new CpuCore();
        cpu.setControlStore(compiledStore);
        for (InstructionDef def : instructions) {
            cpu.setMnemonic(def.opcode(), def.mnemonic());
        }
        cpu.loadProgramBytes(0, program.ramImage());

        while (!cpu.isHalted()) {
            cpu.stepMacro();
        }
        assertEquals(42, cpu.getOutRegUnsigned());

        String html = DatasheetGenerator.generateHtml(instructions, compiledStore);
        assertTrue(html.contains("0x60"));
        assertTrue(html.contains("ADI"));
        assertTrue(html.contains("Add 8-bit immediate operand to Register A"));
        assertTrue(html.contains("Custom"));
    }

    @Test
    void rejectsBusContentionOnSaveAndRollsBackTransaction(@TempDir Path tempDir) throws SQLException {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("micro8-m7-rollback.db"));
        IsaRepository repo = new IsaRepository(dbManager);
        repo.initializeSchemaAndSeed();

        int[][] badSteps = new int[6][4];
        for (int flag = 0; flag < 4; flag++) {
            badSteps[0][flag] = CO | MI;
            badSteps[1][flag] = RO | II | CE;
            badSteps[2][flag] = RO | EO | AI | CLR;
        }
        InstructionDef badDef = new InstructionDef(0x70, "BAD", false, false, "Conflicting bus drivers");

        BusContentionException ex = assertThrows(
                BusContentionException.class,
                () -> repo.saveInstructionMicrocode(badDef, badSteps)
        );
        assertEquals(0x70, ex.opcode());
        assertEquals(2, ex.tState());
        assertTrue(ex.conflictingDrivers().contains("RO"));
        assertTrue(ex.conflictingDrivers().contains("EO"));

        assertEquals(11, repo.listInstructions().size());

        MicrocodeStepRow row = new MicrocodeStepRow(2, 0);
        assertFalse(row.hasBusContention());
        row.setSignal(RO, true);
        assertFalse(row.hasBusContention());
        row.setSignal(EO, true);
        assertTrue(row.hasBusContention());

        repo.resetToDefaultIsa();
        assertEquals(11, repo.listInstructions().size());
    }
}

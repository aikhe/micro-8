package edu.ucc.micro8.asm;

import edu.ucc.micro8.cpu.CpuCore;
import edu.ucc.micro8.db.InstructionDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblerTest {

    @Test
    void assembleCompleteProgramWithForwardBackwardLabelsOrgAndDbExecutesCleanly() {
        String source = """
                ; Loop & sum test program
                START:
                    NOP                 ; line 3 -> 0x00, 0x00
                    LDA NUM_A           ; line 4 -> 0x02, 0x03
                LOOP:
                    ADD STEP_VAL        ; line 6 -> 0x04, 0x05
                    JZ  DONE            ; line 7 -> 0x06, 0x07 (forward reference)
                    JMP FINISH          ; line 8 -> 0x08, 0x09 (forward reference)
                FINISH:
                    STA RESULT_SLOT     ; line 10 -> 0x0A, 0x0B
                    OUT                 ; line 11 -> 0x0C, 0x0D (0x00 padding)
                DONE:
                    HLT                 ; line 13 -> 0x0E, 0x0F (0x00 padding)

                    ORG 0xF0
                NUM_A:
                    DB $18              ; 24
                STEP_VAL:
                    DB 0b00010010       ; 18
                RESULT_SLOT:
                    DB 0x00
                """;

        Assembler assembler = new Assembler();
        AssembledProgram prog = assembler.assemble(source);

        assertTrue(prog.isSuccess(), () -> "Unexpected errors: " + prog.errors());
        assertEquals(19, prog.emittedByteCount());

        byte[] ram = prog.ramImage();
        assertEquals(0x00, Byte.toUnsignedInt(ram[0x00]));
        assertEquals(0x00, Byte.toUnsignedInt(ram[0x01]));
        assertEquals(0x10, Byte.toUnsignedInt(ram[0x02]));
        assertEquals(0xF0, Byte.toUnsignedInt(ram[0x03]));
        assertEquals(0x20, Byte.toUnsignedInt(ram[0x04]));
        assertEquals(0xF1, Byte.toUnsignedInt(ram[0x05]));
        assertEquals(0x41, Byte.toUnsignedInt(ram[0x06]));
        assertEquals(0x0E, Byte.toUnsignedInt(ram[0x07]));
        assertEquals(0x40, Byte.toUnsignedInt(ram[0x08]));
        assertEquals(0x0A, Byte.toUnsignedInt(ram[0x09]));
        assertEquals(0x30, Byte.toUnsignedInt(ram[0x0A]));
        assertEquals(0xF2, Byte.toUnsignedInt(ram[0x0B]));
        assertEquals(0xE0, Byte.toUnsignedInt(ram[0x0C]));
        assertEquals(0x00, Byte.toUnsignedInt(ram[0x0D]));
        assertEquals(0xF0, Byte.toUnsignedInt(ram[0x0E]));
        assertEquals(0x00, Byte.toUnsignedInt(ram[0x0F]));

        assertEquals(24, Byte.toUnsignedInt(ram[0xF0]));
        assertEquals(18, Byte.toUnsignedInt(ram[0xF1]));
        assertEquals(0, Byte.toUnsignedInt(ram[0xF2]));

        assertEquals(3, prog.addressToSourceLine()[0x00]);
        assertEquals(3, prog.addressToSourceLine()[0x01]);
        assertEquals(4, prog.addressToSourceLine()[0x02]);
        assertEquals(4, prog.addressToSourceLine()[0x03]);
        assertEquals(13, prog.addressToSourceLine()[0x0E]);
        assertEquals(-1, prog.addressToSourceLine()[0x50]);

        List<SymbolEntry> symbols = prog.symbolTable();
        assertEquals(7, symbols.size());
        assertEquals("START", symbols.get(0).label());
        assertEquals("0x00", symbols.get(0).addressHex());
        assertEquals("NUM_A", symbols.get(4).label());
        assertEquals("0xF0", symbols.get(4).addressHex());

        CpuCore cpu = new CpuCore();
        cpu.loadProgramBytes(0, ram);
        while (!cpu.isHalted()) {
            assertTrue(cpu.stepMacro());
        }
        assertEquals(42, cpu.getOutRegUnsigned());
        assertEquals(42, cpu.readRamUnsigned(0xF2));
    }

    @Test
    void customInstructionDefinitionsFromDatabaseAreRecognizedAndEmitted() {
        List<InstructionDef> defs = new ArrayList<>(Assembler.defaultInstructions());
        defs.add(new InstructionDef(0x90, "MUL", true, false, "Custom multiply"));

        Assembler assembler = new Assembler(defs);
        AssembledProgram prog = assembler.assemble("""
                LDI 0x06
                MUL 0xF0
                HLT
                """);

        assertTrue(prog.isSuccess());
        assertEquals(6, prog.emittedByteCount());
        assertEquals(0x11, Byte.toUnsignedInt(prog.ramImage()[0]));
        assertEquals(0x06, Byte.toUnsignedInt(prog.ramImage()[1]));
        assertEquals(0x90, Byte.toUnsignedInt(prog.ramImage()[2]));
        assertEquals(0xF0, Byte.toUnsignedInt(prog.ramImage()[3]));
        assertEquals(0xF0, Byte.toUnsignedInt(prog.ramImage()[4]));
        assertEquals(0x00, Byte.toUnsignedInt(prog.ramImage()[5]));
    }

    @Test
    void syntaxErrorsReportExactOneBasedLineNumbersAndOffendingTokens() {
        Assembler assembler = new Assembler();

        AssembledProgram unknownOp = assembler.assemble("LDA 0x10\nFOOBAR 0x20\nHLT");
        assertFalse(unknownOp.isSuccess());
        assertEquals(2, unknownOp.errors().getFirst().lineNumber());
        assertEquals("FOOBAR", unknownOp.errors().getFirst().token());

        AssembledProgram missingOperand = assembler.assemble("LDA\nHLT");
        assertFalse(missingOperand.isSuccess());
        assertEquals(1, missingOperand.errors().getFirst().lineNumber());
        assertEquals("LDA", missingOperand.errors().getFirst().token());

        AssembledProgram unexpectedOperand = assembler.assemble("OUT 0x10\nHLT 0x99");
        assertFalse(unexpectedOperand.isSuccess());
        assertEquals(2, unexpectedOperand.errors().size());
        assertEquals(1, unexpectedOperand.errors().get(0).lineNumber());
        assertEquals("0x10", unexpectedOperand.errors().get(0).token());
        assertEquals(2, unexpectedOperand.errors().get(1).lineNumber());
        assertEquals("0x99", unexpectedOperand.errors().get(1).token());

        AssembledProgram undefinedLabel = assembler.assemble("JMP MISSING_TARGET\nHLT");
        assertFalse(undefinedLabel.isSuccess());
        assertEquals(1, undefinedLabel.errors().getFirst().lineNumber());
        assertEquals("MISSING_TARGET", undefinedLabel.errors().getFirst().token());

        AssembledProgram duplicateLabel = assembler.assemble("LOOP:\nNOP\nLOOP:\nHLT");
        assertFalse(duplicateLabel.isSuccess());
        assertEquals(3, duplicateLabel.errors().getFirst().lineNumber());
        assertEquals("LOOP", duplicateLabel.errors().getFirst().token());

        AssembledProgram outOfRangeLiterals = assembler.assemble("LDI 256\nLDI -5\nLDA 0x1FF");
        assertFalse(outOfRangeLiterals.isSuccess());
        assertEquals(3, outOfRangeLiterals.errors().size());
        assertEquals(1, outOfRangeLiterals.errors().get(0).lineNumber());
        assertEquals("256", outOfRangeLiterals.errors().get(0).token());
        assertEquals(2, outOfRangeLiterals.errors().get(1).lineNumber());
        assertEquals("-5", outOfRangeLiterals.errors().get(1).token());
        assertEquals(3, outOfRangeLiterals.errors().get(2).lineNumber());
        assertEquals("0x1FF", outOfRangeLiterals.errors().get(2).token());

        AssembledProgram ramOverflow = assembler.assemble("ORG 0xFF\nHLT");
        assertFalse(ramOverflow.isSuccess());
        assertEquals(2, ramOverflow.errors().getFirst().lineNumber());
        assertEquals("HLT", ramOverflow.errors().getFirst().token());
    }
}

package edu.ucc.micro8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuCoreTest {

    @Test
    void aluHandlesUnsignedAdditionAndSubtractionEdgeCases() {
        assertEquals(0x00, Alu.computeResult(0xFF, 0x01, false));
        assertTrue(Alu.computeCarry(0xFF, 0x01, false));
        assertTrue(Alu.computeZero(0xFF, 0x01, false));

        assertEquals(0x00, Alu.computeResult(0x80, 0x80, false));
        assertTrue(Alu.computeCarry(0x80, 0x80, false));
        assertTrue(Alu.computeZero(0x80, 0x80, false));

        assertEquals(0xFF, Alu.computeResult(0x80, 0x7F, false));
        assertFalse(Alu.computeCarry(0x80, 0x7F, false));
        assertFalse(Alu.computeZero(0x80, 0x7F, false));

        assertEquals(0x00, Alu.computeResult(0x80, 0x80, true));
        assertTrue(Alu.computeCarry(0x80, 0x80, true));
        assertTrue(Alu.computeZero(0x80, 0x80, true));

        assertEquals(0x05, Alu.computeResult(0xFF, 0xFA, true));
        assertTrue(Alu.computeCarry(0xFF, 0xFA, true));
        assertFalse(Alu.computeZero(0xFF, 0xFA, true));

        assertEquals(0xF0, Alu.computeResult(0x10, 0x20, true));
        assertFalse(Alu.computeCarry(0x10, 0x20, true));
        assertFalse(Alu.computeZero(0x10, 0x20, true));
    }

    @Test
    void stepMicroExecutesProgramCycleByCycleAndFormatsTraceRows() {
        CpuCore cpu = new CpuCore();

        // 0x00: LDA 0xF0 (0x10, 0xF0)
        // 0x02: ADD 0xF1 (0x20, 0xF1)
        // 0x04: OUT      (0xE0, 0x00)
        // 0x06: HLT      (0xF0, 0x00)
        cpu.writeRam(0x00, 0x10);
        cpu.writeRam(0x01, 0xF0);
        cpu.writeRam(0x02, 0x20);
        cpu.writeRam(0x03, 0xF1);
        cpu.writeRam(0x04, 0xE0);
        cpu.writeRam(0x05, 0x00);
        cpu.writeRam(0x06, 0xF0);
        cpu.writeRam(0x07, 0x00);

        cpu.writeRam(0xF0, 0x18); // 24
        cpu.writeRam(0xF1, 0x12); // 18 -> 24 + 18 = 42 (0x2A, '*')

        // LDA 0xF0: T0 (CO|MI)
        assertTrue(cpu.stepMicro());
        assertEquals(1, cpu.getTState());
        assertEquals(0x00, cpu.getMarUnsigned());

        // LDA 0xF0: T1 (RO|II|CE)
        assertTrue(cpu.stepMicro());
        assertEquals(2, cpu.getTState());
        assertEquals(0x10, cpu.getIrUnsigned());
        assertEquals(0x01, cpu.getPcUnsigned());

        // LDA 0xF0: T2 (CO|MI)
        assertTrue(cpu.stepMicro());
        assertEquals(3, cpu.getTState());
        assertEquals(0x01, cpu.getMarUnsigned());

        // LDA 0xF0: T3 (RO|MI|CE)
        assertTrue(cpu.stepMicro());
        assertEquals(4, cpu.getTState());
        assertEquals(0xF0, cpu.getMarUnsigned());
        assertEquals(0x02, cpu.getPcUnsigned());

        // LDA 0xF0: T4 (RO|AI|CLR) -> early CLR resets tState to 0
        assertTrue(cpu.stepMicro());
        assertEquals(0, cpu.getTState());
        assertEquals(0x18, cpu.getRegAUnsigned());

        // ADD 0xF1: 6 ticks (T0..T5)
        for (int t = 0; t < 6; t++) {
            assertEquals(t, cpu.getTState());
            assertTrue(cpu.stepMicro());
        }
        assertEquals(0, cpu.getTState());
        assertEquals(0x04, cpu.getPcUnsigned());
        assertEquals(0x12, cpu.getRegBUnsigned());
        assertEquals(0x2A, cpu.getRegAUnsigned());
        assertFalse(cpu.isZeroFlag());
        assertFalse(cpu.isCarryFlag());

        // OUT: 3 ticks (T0, T1, T2 with CLR)
        assertTrue(cpu.stepMicro());
        assertTrue(cpu.stepMicro());
        assertTrue(cpu.stepMicro());
        assertEquals(0, cpu.getTState());
        assertEquals(0x06, cpu.getPcUnsigned());
        assertEquals(0x2A, cpu.getOutRegUnsigned());

        // HLT: 3 ticks (T0, T1, T2 with HLT|CLR)
        assertTrue(cpu.stepMicro());
        assertTrue(cpu.stepMicro());
        assertTrue(cpu.stepMicro());
        assertEquals(0, cpu.getTState());
        assertEquals(0x08, cpu.getPcUnsigned());
        assertTrue(cpu.isHalted());
        assertFalse(cpu.stepMicro());

        assertEquals(17, cpu.getCycleCount());

        CpuSnapshot snapshot = cpu.getLatestSnapshotRef().get();
        assertNotNull(snapshot);
        assertEquals(17, snapshot.cycleCount());
        assertEquals(0x2A, snapshot.regA());
        assertEquals(0x2A, snapshot.outReg());
        assertTrue(snapshot.halted());

        TraceRow lastRow = null;
        for (TraceRow row : cpu.getTraceQueue()) {
            lastRow = row;
        }
        assertNotNull(lastRow);
        assertEquals("42", lastRow.outDecimal());
        assertEquals("0x2A", lastRow.outHex());
        assertEquals("*", lastRow.outAscii());
    }

    @Test
    void addOverflowAtT5LatchesBothCarryAndZeroBeforeAiOverwritesRegA() {
        CpuCore cpu = new CpuCore();

        // 0x00: LDI 0xFF
        // 0x02: ADD 0xF0 (where RAM[0xF0] = 0x01)
        // 0x04: HLT
        cpu.writeRam(0x00, 0x11);
        cpu.writeRam(0x01, 0xFF);
        cpu.writeRam(0x02, 0x20);
        cpu.writeRam(0x03, 0xF0);
        cpu.writeRam(0x04, 0xF0);
        cpu.writeRam(0x05, 0x00);
        cpu.writeRam(0xF0, 0x01);

        assertTrue(cpu.stepMacro());
        assertEquals(0xFF, cpu.getRegAUnsigned());

        assertTrue(cpu.stepMacro());
        assertEquals(0x00, cpu.getRegAUnsigned());
        assertTrue(cpu.isZeroFlag());
        assertTrue(cpu.isCarryFlag());
    }

    @Test
    void conditionalJumpsHandleHighUnsignedAddressesAndBothFlagStates() {
        CpuCore cpu = new CpuCore();

        // 0x00: LDI 0x80
        // 0x02: SUB 0xF0 (RAM[0xF0] = 0x80 -> result 0x00, Z=1, C=1)
        // 0x04: JZ  0xAA (taken -> PC becomes 0xAA)
        // 0xAA: SUB 0xF1 (RAM[0xF1] = 0x01 -> 0x00 - 0x01 = 0xFF, Z=0, C=0)
        // 0xAC: JZ  0xE0 (not taken -> PC becomes 0xAE)
        // 0xAE: JC  0xE0 (not taken -> PC becomes 0xB0)
        // 0xB0: ADD 0xF1 (0xFF + 0x01 = 0x00, Z=1, C=1)
        // 0xB2: JC  0xF4 (taken -> PC becomes 0xF4)
        // 0xF4: HLT
        cpu.writeRam(0x00, 0x11);
        cpu.writeRam(0x01, 0x80);
        cpu.writeRam(0x02, 0x21);
        cpu.writeRam(0x03, 0xF0);
        cpu.writeRam(0x04, 0x41);
        cpu.writeRam(0x05, 0xAA);

        cpu.writeRam(0xAA, 0x21);
        cpu.writeRam(0xAB, 0xF1);
        cpu.writeRam(0xAC, 0x41);
        cpu.writeRam(0xAD, 0xE0);
        cpu.writeRam(0xAE, 0x42);
        cpu.writeRam(0xAF, 0xE0);
        cpu.writeRam(0xB0, 0x20);
        cpu.writeRam(0xB1, 0xF1);
        cpu.writeRam(0xB2, 0x42);
        cpu.writeRam(0xB3, 0xF4);

        cpu.writeRam(0xF4, 0xF0);
        cpu.writeRam(0xF5, 0x00);

        cpu.writeRam(0xF0, 0x80);
        cpu.writeRam(0xF1, 0x01);

        // LDI 0x80
        assertTrue(cpu.stepMacro());
        assertEquals(0x80, cpu.getRegAUnsigned());

        // SUB 0xF0 -> 0x80 - 0x80 = 0x00 (Z=1, C=1)
        assertTrue(cpu.stepMacro());
        assertEquals(0x00, cpu.getRegAUnsigned());
        assertTrue(cpu.isZeroFlag());
        assertTrue(cpu.isCarryFlag());

        // JZ 0xAA -> taken
        assertTrue(cpu.stepMacro());
        assertEquals(0xAA, cpu.getPcUnsigned());

        // SUB 0xF1 -> 0x00 - 0x01 = 0xFF (Z=0, C=0)
        assertTrue(cpu.stepMacro());
        assertEquals(0xFF, cpu.getRegAUnsigned());
        assertFalse(cpu.isZeroFlag());
        assertFalse(cpu.isCarryFlag());

        // JZ 0xE0 -> not taken, advances to 0xAE
        assertTrue(cpu.stepMacro());
        assertEquals(0xAE, cpu.getPcUnsigned());

        // JC 0xE0 -> not taken, advances to 0xB0
        assertTrue(cpu.stepMacro());
        assertEquals(0xB0, cpu.getPcUnsigned());

        // ADD 0xF1 -> 0xFF + 0x01 = 0x00 (Z=1, C=1)
        assertTrue(cpu.stepMacro());
        assertEquals(0x00, cpu.getRegAUnsigned());
        assertTrue(cpu.isZeroFlag());
        assertTrue(cpu.isCarryFlag());

        // JC 0xF4 -> taken
        assertTrue(cpu.stepMacro());
        assertEquals(0xF4, cpu.getPcUnsigned());

        // HLT at 0xF4
        assertTrue(cpu.stepMacro());
        assertTrue(cpu.isHalted());
        assertEquals(0xF6, cpu.getPcUnsigned());
    }
}

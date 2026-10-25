package edu.ucc.micro8.cpu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaJournalTest {

    @Test
    void packAndUnpackPreservesHighUnsignedByteValuesWithoutSignExtension() {
        long packed80 = DeltaJournal.pack(99_999, 5, DeltaJournal.TARGET_RAM_BYTE, 0xFF, 0x80);
        assertEquals(99_999, DeltaJournal.unpackCycle(packed80));
        assertEquals(5, DeltaJournal.unpackTState(packed80));
        assertEquals(DeltaJournal.TARGET_RAM_BYTE, DeltaJournal.unpackTarget(packed80));
        assertEquals(0xFF, DeltaJournal.unpackAddress(packed80));
        assertEquals(0x80, DeltaJournal.unpackOldValue(packed80));

        long packedFF = DeltaJournal.pack(1_234_567, 4, DeltaJournal.TARGET_REG_A, 0xAA, 0xFF);
        assertEquals(1_234_567, DeltaJournal.unpackCycle(packedFF));
        assertEquals(4, DeltaJournal.unpackTState(packedFF));
        assertEquals(DeltaJournal.TARGET_REG_A, DeltaJournal.unpackTarget(packedFF));
        assertEquals(0xAA, DeltaJournal.unpackAddress(packedFF));
        assertEquals(0xFF, DeltaJournal.unpackOldValue(packedFF));
    }

    @Test
    void ringBufferWrapsAtTenThousandEntriesInExactLifoOrder() {
        DeltaJournal journal = new DeltaJournal();
        int totalPushed = 12_500;

        for (int i = 0; i < totalPushed; i++) {
            journal.record(i, i % 6, DeltaJournal.TARGET_RAM_BYTE, i & 0xFF, (i ^ 0xA5) & 0xFF);
        }

        assertEquals(DeltaJournal.CAPACITY, journal.size());
        assertEquals(totalPushed - 1, journal.peekNewestCycle());

        for (int expectedCycle = totalPushed - 1; expectedCycle >= totalPushed - DeltaJournal.CAPACITY; expectedCycle--) {
            assertFalse(journal.isEmpty());
            assertEquals(expectedCycle, journal.peekNewestCycle());
            long entry = journal.popNewest();
            assertEquals(expectedCycle, DeltaJournal.unpackCycle(entry));
            assertEquals(expectedCycle % 6, DeltaJournal.unpackTState(entry));
            assertEquals(DeltaJournal.TARGET_RAM_BYTE, DeltaJournal.unpackTarget(entry));
            assertEquals(expectedCycle & 0xFF, DeltaJournal.unpackAddress(entry));
            assertEquals((expectedCycle ^ 0xA5) & 0xFF, DeltaJournal.unpackOldValue(entry));
        }

        assertTrue(journal.isEmpty());
        assertEquals(0, journal.size());
    }

    @Test
    void undoMicroStepAndUndoMacroStepRestoreExactTrajectoryBitForBit() {
        CpuCore cpu = new CpuCore();

        // 0x00: LDI 0x80       (RegA = 0x80)
        // 0x02: ADD 0xF0       (RAM[0xF0] = 0x80 -> RegA = 0x00, RegB = 0x80, Z=1, C=1)
        // 0x04: SUB 0xF1       (RAM[0xF1] = 0x05 -> RegA = 0xFB, RegB = 0x05, Z=0, C=0)
        // 0x06: STA 0xF2       (RAM[0xF2] = 0xFB)
        // 0x08: OUT            (OutReg = 0xFB)
        // 0x0A: HLT            (halted = true)
        cpu.writeRam(0x00, 0x11);
        cpu.writeRam(0x01, 0x80);
        cpu.writeRam(0x02, 0x20);
        cpu.writeRam(0x03, 0xF0);
        cpu.writeRam(0x04, 0x21);
        cpu.writeRam(0x05, 0xF1);
        cpu.writeRam(0x06, 0x30);
        cpu.writeRam(0x07, 0xF2);
        cpu.writeRam(0x08, 0xE0);
        cpu.writeRam(0x09, 0x00);
        cpu.writeRam(0x0A, 0xF0);
        cpu.writeRam(0x0B, 0x00);

        cpu.writeRam(0xF0, 0x80);
        cpu.writeRam(0xF1, 0x05);
        cpu.writeRam(0xF2, 0x99);

        List<CpuSnapshot> forwardMicroSnapshots = new ArrayList<>();
        List<CpuSnapshot> instructionBoundarySnapshots = new ArrayList<>();

        forwardMicroSnapshots.add(cpu.createSnapshot());
        instructionBoundarySnapshots.add(cpu.createSnapshot());

        while (!cpu.isHalted()) {
            assertTrue(cpu.stepMicro());
            CpuSnapshot snap = cpu.createSnapshot();
            forwardMicroSnapshots.add(snap);
            if (snap.tState() == 0 && !snap.halted()) {
                instructionBoundarySnapshots.add(snap);
            }
        }

        assertTrue(cpu.isHalted());
        assertEquals(0xFB, cpu.readRamUnsigned(0xF2));
        assertEquals(0xFB, cpu.getOutRegUnsigned());

        // Walk backward micro-step by micro-step to cycle 0
        for (int idx = forwardMicroSnapshots.size() - 2; idx >= 0; idx--) {
            assertTrue(cpu.undoMicroStep());
            assertSnapshotsIdentical(forwardMicroSnapshots.get(idx), cpu.createSnapshot());
        }

        assertFalse(cpu.undoMicroStep());
        assertEquals(0, cpu.getCycleCount());
        assertEquals(0x99, cpu.readRamUnsigned(0xF2));

        // Run forward to HLT again, then walk backward macro-step by macro-step
        while (!cpu.isHalted()) {
            assertTrue(cpu.stepMacro());
        }
        assertTrue(cpu.isHalted());

        for (int i = instructionBoundarySnapshots.size() - 1; i >= 0; i--) {
            int undone = cpu.undoMacroStep();
            assertTrue(undone > 0);
            assertEquals(0, cpu.getTState());
            assertSnapshotsIdentical(instructionBoundarySnapshots.get(i), cpu.createSnapshot());
        }

        assertEquals(0, cpu.undoMacroStep());
    }

    private static void assertSnapshotsIdentical(CpuSnapshot expected, CpuSnapshot actual) {
        assertEquals(expected.cycleCount(), actual.cycleCount());
        assertEquals(expected.tState(), actual.tState());
        assertEquals(expected.pc(), actual.pc());
        assertEquals(expected.mar(), actual.mar());
        assertEquals(expected.ir(), actual.ir());
        assertEquals(expected.regA(), actual.regA());
        assertEquals(expected.regB(), actual.regB());
        assertEquals(expected.outReg(), actual.outReg());
        assertEquals(expected.aluResult(), actual.aluResult());
        assertEquals(expected.zeroFlag(), actual.zeroFlag());
        assertEquals(expected.carryFlag(), actual.carryFlag());
        assertEquals(expected.halted(), actual.halted());
        assertEquals(expected.busValue(), actual.busValue());
        assertEquals(expected.controlWord(), actual.controlWord());
        assertArrayEquals(expected.ramCopy(), actual.ramCopy());
    }
}

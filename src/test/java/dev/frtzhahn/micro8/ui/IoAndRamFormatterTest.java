package dev.frtzhahn.micro8.ui;

import dev.frtzhahn.micro8.cpu.CpuCore;
import dev.frtzhahn.micro8.cpu.TraceRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_A;
import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_B;
import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_C;
import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_D;
import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_E;
import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_F;
import static dev.frtzhahn.micro8.ui.SevenSegmentCanvas.SEG_G;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoAndRamFormatterTest {

    @Test
    void sevenSegmentHexEncodingAndNibbleSplittingHandleUnsignedBytes() {
        assertEquals(SEG_A | SEG_B | SEG_C | SEG_D | SEG_E | SEG_F, SevenSegmentCanvas.encodeHexDigit(0x0));
        assertEquals(SEG_B | SEG_C, SevenSegmentCanvas.encodeHexDigit(0x1));
        assertEquals(SEG_A | SEG_B | SEG_C | SEG_D | SEG_E | SEG_F | SEG_G, SevenSegmentCanvas.encodeHexDigit(0x8));
        assertEquals(SEG_A | SEG_B | SEG_C | SEG_E | SEG_F | SEG_G, SevenSegmentCanvas.encodeHexDigit(0xA));
        assertEquals(SEG_A | SEG_F | SEG_G | SEG_E, SevenSegmentCanvas.encodeHexDigit(0xF));

        assertEquals(0x0, SevenSegmentCanvas.extractHighNibble(0x00));
        assertEquals(0x0, SevenSegmentCanvas.extractLowNibble(0x00));

        assertEquals(0x2, SevenSegmentCanvas.extractHighNibble(0x2A));
        assertEquals(0xA, SevenSegmentCanvas.extractLowNibble(0x2A));

        assertEquals(0x8, SevenSegmentCanvas.extractHighNibble(0x80));
        assertEquals(0x0, SevenSegmentCanvas.extractLowNibble(0x80));

        assertEquals(0xF, SevenSegmentCanvas.extractHighNibble(0xFF));
        assertEquals(0xF, SevenSegmentCanvas.extractLowNibble(0xFF));
    }

    @Test
    void binaryLedBitExtractionHandlesSignedBoundaryBytesWithoutSignExtension() {
        // 0x80 = 1000_0000
        assertEquals(1, SevenSegmentCanvas.extractBit(0x80, 7));
        for (int bit = 0; bit < 7; bit++) {
            assertEquals(0, SevenSegmentCanvas.extractBit(0x80, bit));
        }

        // 0xFF = 1111_1111
        for (int bit = 0; bit < 8; bit++) {
            assertEquals(1, SevenSegmentCanvas.extractBit(0xFF, bit));
        }

        // 0x2A = 0010_1010 (42)
        assertEquals(0, SevenSegmentCanvas.extractBit(0x2A, 7));
        assertEquals(0, SevenSegmentCanvas.extractBit(0x2A, 6));
        assertEquals(1, SevenSegmentCanvas.extractBit(0x2A, 5));
        assertEquals(0, SevenSegmentCanvas.extractBit(0x2A, 4));
        assertEquals(1, SevenSegmentCanvas.extractBit(0x2A, 3));
        assertEquals(0, SevenSegmentCanvas.extractBit(0x2A, 2));
        assertEquals(1, SevenSegmentCanvas.extractBit(0x2A, 1));
        assertEquals(0, SevenSegmentCanvas.extractBit(0x2A, 0));

        assertThrows(IllegalArgumentException.class, () -> SevenSegmentCanvas.extractBit(0x2A, -1));
        assertThrows(IllegalArgumentException.class, () -> SevenSegmentCanvas.extractBit(0x2A, 8));
    }

    @Test
    void parseHexDumpLoadsProgramIntoCpuRamAndPrunesTraceOnUndo() {
        byte[] ramImage = RamHexGridView.parseHexDump("11 2A E0 00 F0 00");
        assertEquals(0x11, Byte.toUnsignedInt(ramImage[0]));
        assertEquals(0x2A, Byte.toUnsignedInt(ramImage[1]));
        assertEquals(0xE0, Byte.toUnsignedInt(ramImage[2]));
        assertEquals(0x00, Byte.toUnsignedInt(ramImage[3]));
        assertEquals(0xF0, Byte.toUnsignedInt(ramImage[4]));
        assertEquals(0x00, Byte.toUnsignedInt(ramImage[5]));

        CpuCore cpu = new CpuCore();
        cpu.loadProgramBytes(0, ramImage);
        while (!cpu.isHalted()) {
            assertTrue(cpu.stepMacro());
        }
        assertEquals(0x2A, cpu.getOutRegUnsigned());

        List<TraceRow> drainedRows = new ArrayList<>();
        TraceRow row;
        while ((row = cpu.getTraceQueue().poll()) != null) {
            drainedRows.add(row);
        }
        assertEquals(10, drainedRows.size());
        assertEquals("42", drainedRows.getLast().outDecimal());
        assertEquals("0x2A", drainedRows.getLast().outHex());
        assertEquals("*", drainedRows.getLast().outAscii());

        // Undo the last macro step (HLT = 3 cycles, landing at cycle 7) and prune trace rows
        int undone = cpu.undoMacroStep();
        assertEquals(3, undone);
        assertEquals(7, cpu.getCycleCount());

        RamHexGridView.pruneTraceRowsAfterCycle(drainedRows, cpu.getCycleCount());
        assertEquals(7, drainedRows.size());
        assertEquals(7, drainedRows.getLast().cycleCount());

        assertThrows(IllegalArgumentException.class, () -> RamHexGridView.parseSingleHexByte("0x1FF"));
        assertThrows(IllegalArgumentException.class, () -> RamHexGridView.parseSingleHexByte("ZZ"));
    }
}

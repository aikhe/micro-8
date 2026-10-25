package edu.ucc.micro8.cpu;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static edu.ucc.micro8.cpu.ControlSignals.AI;
import static edu.ucc.micro8.cpu.ControlSignals.AO;
import static edu.ucc.micro8.cpu.ControlSignals.BI;
import static edu.ucc.micro8.cpu.ControlSignals.BO;
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

/**
 * Cycle-accurate 8-bit microcoded CPU engine with 2-byte uniform ISA and T0-T5 ring counter.
 */
public final class CpuCore {

    public static final int RAM_SIZE = 256;
    private static final int MAX_TRACE_QUEUE_SIZE = 4096;

    private final byte[] ram = new byte[RAM_SIZE];
    private byte regA;
    private byte regB;
    private byte pc;
    private byte mar;
    private byte ir;
    private byte outReg;

    private boolean zeroFlag;
    private boolean carryFlag;
    private boolean halted;

    private int tState;
    private int cycleCount;
    private int lastBusValue;
    private int lastControlWord;

    private int[][][] controlStore;
    private final String[] mnemonicTable = new String[256];
    private boolean traceEnabled = true;

    private final DeltaJournal journal = new DeltaJournal();
    private final AtomicReference<CpuSnapshot> latestSnapshot = new AtomicReference<>();
    private final ConcurrentLinkedQueue<TraceRow> traceQueue = new ConcurrentLinkedQueue<>();

    public CpuCore() {
        this(buildDefaultControlStore());
    }

    public CpuCore(int[][][] controlStore) {
        initDefaultMnemonics();
        setControlStore(controlStore);
        publishSnapshot();
    }

    public void setControlStore(int[][][] controlStore) {
        Objects.requireNonNull(controlStore, "controlStore must not be null");
        if (controlStore.length != 256 || controlStore[0].length != 6 || controlStore[0][0].length != 4) {
            throw new IllegalArgumentException("controlStore dimensions must be int[256][6][4]");
        }
        this.controlStore = controlStore;
    }

    public int[][][] getControlStore() {
        return controlStore;
    }

    public DeltaJournal getJournal() {
        return journal;
    }

    public void setMnemonic(int opcode, String mnemonic) {
        mnemonicTable[opcode & 0xFF] = (mnemonic == null || mnemonic.isBlank()) ? "???" : mnemonic;
    }

    public String getMnemonic(int opcode) {
        return mnemonicTable[opcode & 0xFF];
    }

    public void setTraceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public boolean stepMicro() {
        if (halted) {
            return false;
        }

        int currentCycle = cycleCount;
        int executedTState = tState;
        int flagIdx = (zeroFlag ? 1 : 0) | (carryFlag ? 2 : 0);
        int opcode = Byte.toUnsignedInt(ir);
        int ctrl = controlStore[opcode][executedTState][flagIdx];

        if (ControlSignals.hasBusContention(ctrl)) {
            throw new IllegalStateException(
                    "Bus contention at T" + executedTState + " for opcode 0x" + Integer.toHexString(opcode).toUpperCase()
            );
        }

        // evaluate ALU against pre-latch RegA/RegB so EO | AI | FI on the same edge latches flags before AI updates RegA
        boolean subtract = (ctrl & SU) != 0;
        int aUnsigned = Byte.toUnsignedInt(regA);
        int bUnsigned = Byte.toUnsignedInt(regB);
        int aluPacked = Alu.evaluatePacked(aUnsigned, bUnsigned, subtract);
        int aluResult = Alu.unpackResult(aluPacked);
        boolean aluCarry = Alu.unpackCarry(aluPacked);
        boolean aluZero = Alu.unpackZero(aluPacked);

        // Phase A: bus drivers update busVal before consumers latch on the same tState
        int busVal = 0;
        if ((ctrl & RO) != 0) {
            busVal = Byte.toUnsignedInt(ram[Byte.toUnsignedInt(mar)]);
        } else if ((ctrl & AO) != 0) {
            busVal = aUnsigned;
        } else if ((ctrl & BO) != 0) {
            busVal = bUnsigned;
        } else if ((ctrl & EO) != 0) {
            busVal = aluResult;
        } else if ((ctrl & CO) != 0) {
            busVal = Byte.toUnsignedInt(pc);
        }

        journal.record(currentCycle, executedTState, DeltaJournal.TARGET_T_STATE, 0, executedTState);
        if (lastBusValue != (busVal & 0xFF)) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_BUS, 0, lastBusValue & 0xFF);
        }
        if (lastControlWord != ctrl) {
            journal.record(
                    currentCycle,
                    executedTState,
                    DeltaJournal.TARGET_CTRL_WORD_LOW,
                    (lastControlWord >>> 8) & 0xFF,
                    lastControlWord & 0xFF
            );
            journal.record(
                    currentCycle,
                    executedTState,
                    DeltaJournal.TARGET_CTRL_WORD_HIGH,
                    (lastControlWord >>> 24) & 0xFF,
                    (lastControlWord >>> 16) & 0xFF
            );
        }

        // Phase B: record pre-mutation state into DeltaJournal, then latch consumers
        if ((ctrl & MI) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_MAR, 0, Byte.toUnsignedInt(mar));
            mar = (byte) (busVal & 0xFF);
        }
        if ((ctrl & RI) != 0) {
            int ramAddr = Byte.toUnsignedInt(mar);
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_RAM_BYTE, ramAddr, Byte.toUnsignedInt(ram[ramAddr]));
            ram[ramAddr] = (byte) (busVal & 0xFF);
        }
        if ((ctrl & II) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_IR, 0, Byte.toUnsignedInt(ir));
            ir = (byte) (busVal & 0xFF);
        }
        if ((ctrl & AI) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_REG_A, 0, Byte.toUnsignedInt(regA));
            regA = (byte) (busVal & 0xFF);
        }
        if ((ctrl & BI) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_REG_B, 0, Byte.toUnsignedInt(regB));
            regB = (byte) (busVal & 0xFF);
        }
        if ((ctrl & OI) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_OUT_REG, 0, Byte.toUnsignedInt(outReg));
            outReg = (byte) (busVal & 0xFF);
        }
        if ((ctrl & J) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_PC, 0, Byte.toUnsignedInt(pc));
            pc = (byte) (busVal & 0xFF);
        }
        if ((ctrl & CE) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_PC, 0, Byte.toUnsignedInt(pc));
            pc = (byte) ((Byte.toUnsignedInt(pc) + 1) & 0xFF);
        }
        if ((ctrl & FI) != 0) {
            int oldFlags = (zeroFlag ? 1 : 0) | (carryFlag ? 2 : 0);
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_FLAGS, 0, oldFlags);
            zeroFlag = aluZero;
            carryFlag = aluCarry;
        }
        if ((ctrl & HLT) != 0) {
            journal.record(currentCycle, executedTState, DeltaJournal.TARGET_HALTED, 0, halted ? 1 : 0);
            halted = true;
        }

        // Phase C: advance sequencer and publish snapshot/trace
        lastBusValue = busVal & 0xFF;
        lastControlWord = ctrl;
        cycleCount++;

        if ((ctrl & CLR) != 0 || executedTState >= 5) {
            tState = 0;
        } else {
            tState = executedTState + 1;
        }

        int finalOpcode = Byte.toUnsignedInt(ir);
        publishSnapshot();
        if (traceEnabled) {
            if (traceQueue.size() >= MAX_TRACE_QUEUE_SIZE) {
                traceQueue.poll();
            }
            traceQueue.offer(new TraceRow(
                    cycleCount,
                    executedTState,
                    Byte.toUnsignedInt(pc),
                    finalOpcode,
                    mnemonicTable[finalOpcode],
                    lastBusValue,
                    Byte.toUnsignedInt(regA),
                    Byte.toUnsignedInt(regB),
                    Byte.toUnsignedInt(mar),
                    zeroFlag,
                    carryFlag,
                    Byte.toUnsignedInt(outReg),
                    lastControlWord
            ));
        }

        return true;
    }

    public boolean stepMacro() {
        if (halted) {
            return false;
        }
        do {
            if (!stepMicro()) {
                break;
            }
        } while (tState != 0 && !halted);
        return true;
    }

    public boolean undoMicroStep() {
        if (journal.isEmpty()) {
            return false;
        }
        int targetCycle = journal.peekNewestCycle();
        while (!journal.isEmpty() && journal.peekNewestCycle() == targetCycle) {
            long entry = journal.popNewest();
            int target = DeltaJournal.unpackTarget(entry);
            int addr = DeltaJournal.unpackAddress(entry);
            int oldVal = DeltaJournal.unpackOldValue(entry);

            switch (target) {
                case DeltaJournal.TARGET_REG_A -> regA = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_REG_B -> regB = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_PC -> pc = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_MAR -> mar = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_IR -> ir = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_OUT_REG -> outReg = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_FLAGS -> {
                    zeroFlag = (oldVal & 1) != 0;
                    carryFlag = (oldVal & 2) != 0;
                }
                case DeltaJournal.TARGET_T_STATE -> tState = oldVal & 0xFF;
                case DeltaJournal.TARGET_HALTED -> halted = (oldVal != 0);
                case DeltaJournal.TARGET_RAM_BYTE -> ram[addr & 0xFF] = (byte) (oldVal & 0xFF);
                case DeltaJournal.TARGET_BUS -> lastBusValue = oldVal & 0xFF;
                case DeltaJournal.TARGET_CTRL_WORD_LOW ->
                        lastControlWord = (lastControlWord & 0xFFFF0000) | ((addr & 0xFF) << 8) | (oldVal & 0xFF);
                case DeltaJournal.TARGET_CTRL_WORD_HIGH ->
                        lastControlWord = (lastControlWord & 0x0000FFFF) | ((addr & 0xFF) << 24) | ((oldVal & 0xFF) << 16);
                default -> throw new IllegalStateException("Unknown DeltaJournal target ID: " + target);
            }
        }
        cycleCount = targetCycle;
        publishSnapshot();
        return true;
    }

    public int undoMacroStep() {
        if (journal.isEmpty()) {
            return 0;
        }
        int undone = 0;
        do {
            if (!undoMicroStep()) {
                break;
            }
            undone++;
        } while (tState != 0 && !journal.isEmpty());
        return undone;
    }

    public void reset() {
        regA = 0;
        regB = 0;
        pc = 0;
        mar = 0;
        ir = 0;
        outReg = 0;
        zeroFlag = false;
        carryFlag = false;
        halted = false;
        tState = 0;
        cycleCount = 0;
        lastBusValue = 0;
        lastControlWord = 0;
        journal.clear();
        traceQueue.clear();
        publishSnapshot();
    }

    public void clearRam() {
        Arrays.fill(ram, (byte) 0);
        publishSnapshot();
    }

    public void wipeCleanSlate() {
        Arrays.fill(ram, (byte) 0);
        reset();
    }

    public int readRamUnsigned(int addr) {
        return Byte.toUnsignedInt(ram[addr & 0xFF]);
    }

    public void writeRam(int addr, int val) {
        ram[addr & 0xFF] = (byte) (val & 0xFF);
    }

    public void loadProgramBytes(int startAddr, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        for (int i = 0; i < bytes.length; i++) {
            ram[(startAddr + i) & 0xFF] = bytes[i];
        }
        publishSnapshot();
    }

    public byte[] copyRam() {
        return Arrays.copyOf(ram, RAM_SIZE);
    }

    public int getRegAUnsigned() {
        return Byte.toUnsignedInt(regA);
    }

    public void setRegA(int val) {
        regA = (byte) (val & 0xFF);
    }

    public int getRegBUnsigned() {
        return Byte.toUnsignedInt(regB);
    }

    public void setRegB(int val) {
        regB = (byte) (val & 0xFF);
    }

    public int getPcUnsigned() {
        return Byte.toUnsignedInt(pc);
    }

    public void setPc(int val) {
        pc = (byte) (val & 0xFF);
    }

    public int getMarUnsigned() {
        return Byte.toUnsignedInt(mar);
    }

    public void setMar(int val) {
        mar = (byte) (val & 0xFF);
    }

    public int getIrUnsigned() {
        return Byte.toUnsignedInt(ir);
    }

    public void setIr(int val) {
        ir = (byte) (val & 0xFF);
    }

    public int getOutRegUnsigned() {
        return Byte.toUnsignedInt(outReg);
    }

    public void setOutReg(int val) {
        outReg = (byte) (val & 0xFF);
    }

    public boolean isZeroFlag() {
        return zeroFlag;
    }

    public void setZeroFlag(boolean zeroFlag) {
        this.zeroFlag = zeroFlag;
    }

    public boolean isCarryFlag() {
        return carryFlag;
    }

    public void setCarryFlag(boolean carryFlag) {
        this.carryFlag = carryFlag;
    }

    public boolean isHalted() {
        return halted;
    }

    public void setHalted(boolean halted) {
        this.halted = halted;
    }

    public int getTState() {
        return tState;
    }

    public void setTState(int tState) {
        this.tState = Math.clamp(tState, 0, 5);
    }

    public int getCycleCount() {
        return cycleCount;
    }

    public int getLastBusValue() {
        return lastBusValue & 0xFF;
    }

    public int getLastControlWord() {
        return lastControlWord;
    }

    public AtomicReference<CpuSnapshot> getLatestSnapshotRef() {
        return latestSnapshot;
    }

    public CpuSnapshot consumeLatestSnapshot() {
        return latestSnapshot.getAndSet(null);
    }

    public ConcurrentLinkedQueue<TraceRow> getTraceQueue() {
        return traceQueue;
    }

    public CpuSnapshot createSnapshot() {
        boolean subtract = (lastControlWord & SU) != 0;
        int aluResult = Alu.computeResult(Byte.toUnsignedInt(regA), Byte.toUnsignedInt(regB), subtract);
        return new CpuSnapshot(
                cycleCount,
                tState,
                Byte.toUnsignedInt(pc),
                Byte.toUnsignedInt(mar),
                Byte.toUnsignedInt(ir),
                Byte.toUnsignedInt(regA),
                Byte.toUnsignedInt(regB),
                Byte.toUnsignedInt(outReg),
                aluResult,
                zeroFlag,
                carryFlag,
                halted,
                lastBusValue,
                lastControlWord,
                Arrays.copyOf(ram, RAM_SIZE)
        );
    }

    private void publishSnapshot() {
        latestSnapshot.set(createSnapshot());
    }

    private void initDefaultMnemonics() {
        Arrays.fill(mnemonicTable, "???");
        mnemonicTable[0x00] = "NOP";
        mnemonicTable[0x10] = "LDA";
        mnemonicTable[0x11] = "LDI";
        mnemonicTable[0x20] = "ADD";
        mnemonicTable[0x21] = "SUB";
        mnemonicTable[0x30] = "STA";
        mnemonicTable[0x40] = "JMP";
        mnemonicTable[0x41] = "JZ";
        mnemonicTable[0x42] = "JC";
        mnemonicTable[0xE0] = "OUT";
        mnemonicTable[0xF0] = "HLT";
    }

    public static int[][][] buildDefaultControlStore() {
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

        for (int flag = 0; flag < 4; flag++) {
            store[0x00][2][flag] = CE | CLR;

            store[0x10][2][flag] = CO | MI;
            store[0x10][3][flag] = RO | MI | CE;
            store[0x10][4][flag] = RO | AI | CLR;

            store[0x11][2][flag] = CO | MI;
            store[0x11][3][flag] = RO | AI | CE | CLR;

            store[0x20][2][flag] = CO | MI;
            store[0x20][3][flag] = RO | MI | CE;
            store[0x20][4][flag] = RO | BI;
            store[0x20][5][flag] = EO | AI | FI | CLR;

            store[0x21][2][flag] = CO | MI;
            store[0x21][3][flag] = RO | MI | CE;
            store[0x21][4][flag] = RO | BI;
            store[0x21][5][flag] = EO | SU | AI | FI | CLR;

            store[0x30][2][flag] = CO | MI;
            store[0x30][3][flag] = RO | MI | CE;
            store[0x30][4][flag] = AO | RI | CLR;

            store[0x40][2][flag] = CO | MI;
            store[0x40][3][flag] = RO | J | CLR;

            store[0x41][2][flag] = CO | MI;
            store[0x41][3][flag] = ((flag & 1) != 0) ? (RO | J | CLR) : (CE | CLR);

            store[0x42][2][flag] = CO | MI;
            store[0x42][3][flag] = ((flag & 2) != 0) ? (RO | J | CLR) : (CE | CLR);

            store[0xE0][2][flag] = AO | OI | CE | CLR;
            store[0xF0][2][flag] = CE | HLT | CLR;
        }

        return store;
    }
}

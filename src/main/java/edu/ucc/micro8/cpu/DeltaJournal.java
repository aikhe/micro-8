package edu.ucc.micro8.cpu;

/**
 * Bounded zero-allocation 64-bit ring buffer for cycle-accurate reverse debugging.
 */
public final class DeltaJournal {

    public static final int CAPACITY = 10_000;

    public static final int TARGET_REG_A          = 0;
    public static final int TARGET_REG_B          = 1;
    public static final int TARGET_PC             = 2;
    public static final int TARGET_MAR            = 3;
    public static final int TARGET_IR             = 4;
    public static final int TARGET_OUT_REG        = 5;
    public static final int TARGET_FLAGS          = 6;
    public static final int TARGET_T_STATE        = 7;
    public static final int TARGET_HALTED         = 8;
    public static final int TARGET_RAM_BYTE       = 9;
    public static final int TARGET_BUS            = 10;
    public static final int TARGET_CTRL_WORD_LOW  = 11;
    public static final int TARGET_CTRL_WORD_HIGH = 12;

    private final long[] ring = new long[CAPACITY];
    private int head;
    private int size;

    public static long pack(int cycleCount, int tState, int target, int address, int oldValue) {
        return ((long) cycleCount << 32)
                | ((tState & 0xFFL) << 24)
                | ((target & 0xFFL) << 16)
                | ((address & 0xFFL) << 8)
                | (oldValue & 0xFFL);
    }

    public static int unpackCycle(long entry) {
        return (int) (entry >>> 32);
    }

    public static int unpackTState(long entry) {
        return (int) ((entry >>> 24) & 0xFFL);
    }

    public static int unpackTarget(long entry) {
        return (int) ((entry >>> 16) & 0xFFL);
    }

    public static int unpackAddress(long entry) {
        return (int) ((entry >>> 8) & 0xFFL);
    }

    public static int unpackOldValue(long entry) {
        return (int) (entry & 0xFFL);
    }

    public void record(int cycleCount, int tState, int target, int address, int oldValue) {
        ring[head] = pack(cycleCount, tState, target, address, oldValue);
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    public int peekNewestCycle() {
        if (size == 0) {
            throw new IllegalStateException("DeltaJournal is empty");
        }
        int newestIdx = (head - 1 + CAPACITY) % CAPACITY;
        return unpackCycle(ring[newestIdx]);
    }

    public long popNewest() {
        if (size == 0) {
            throw new IllegalStateException("DeltaJournal is empty");
        }
        head = (head - 1 + CAPACITY) % CAPACITY;
        size--;
        return ring[head];
    }
}

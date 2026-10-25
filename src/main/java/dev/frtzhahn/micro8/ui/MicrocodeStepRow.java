package dev.frtzhahn.micro8.ui;

import dev.frtzhahn.micro8.cpu.ControlSignals;

/**
 * Mutable row model for a single T-state (T0..T5) in the Control-Store Microcode Designer table.
 */
public final class MicrocodeStepRow {

    private final int tState;
    private int controlWord;

    public MicrocodeStepRow(int tState, int controlWord) {
        if (tState < 0 || tState > 5) {
            throw new IllegalArgumentException("tState must be between 0 and 5");
        }
        this.tState = tState;
        this.controlWord = controlWord;
    }

    public int getTState() {
        return tState;
    }

    public String getTStateLabel() {
        return "T" + tState;
    }

    public int getControlWord() {
        return controlWord;
    }

    public void setControlWord(int controlWord) {
        this.controlWord = controlWord;
    }

    public boolean isSignalSet(int signalMask) {
        return (controlWord & signalMask) != 0;
    }

    public void setSignal(int signalMask, boolean enabled) {
        if (enabled) {
            controlWord |= signalMask;
        } else {
            controlWord &= ~signalMask;
        }
    }

    public boolean hasBusContention() {
        return ControlSignals.hasBusContention(controlWord);
    }

    public int getActiveBusDriverCount() {
        return Integer.bitCount(controlWord & ControlSignals.BUS_DRIVER_MASK);
    }

    public String getHexWord() {
        return String.format("0x%08X", controlWord);
    }

    public String getActiveSignalsSummary() {
        return ControlSignals.formatActiveSignals(controlWord);
    }
}

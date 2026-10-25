package edu.ucc.micro8.cpu;

import java.util.List;
import java.util.StringJoiner;

public final class ControlSignals {

    public static final int BIT_HLT = 0;
    public static final int BIT_MI  = 1;
    public static final int BIT_RI  = 2;
    public static final int BIT_RO  = 3;
    public static final int BIT_II  = 4;
    public static final int BIT_AI  = 5;
    public static final int BIT_AO  = 6;
    public static final int BIT_BI  = 7;
    public static final int BIT_BO  = 8;
    public static final int BIT_EO  = 9;
    public static final int BIT_SU  = 10;
    public static final int BIT_OI  = 11;
    public static final int BIT_CE  = 12;
    public static final int BIT_CO  = 13;
    public static final int BIT_J   = 14;
    public static final int BIT_FI  = 15;
    public static final int BIT_CLR = 16;

    public static final int HLT = 1 << BIT_HLT;
    public static final int MI  = 1 << BIT_MI;
    public static final int RI  = 1 << BIT_RI;
    public static final int RO  = 1 << BIT_RO;
    public static final int II  = 1 << BIT_II;
    public static final int AI  = 1 << BIT_AI;
    public static final int AO  = 1 << BIT_AO;
    public static final int BI  = 1 << BIT_BI;
    public static final int BO  = 1 << BIT_BO;
    public static final int EO  = 1 << BIT_EO;
    public static final int SU  = 1 << BIT_SU;
    public static final int OI  = 1 << BIT_OI;
    public static final int CE  = 1 << BIT_CE;
    public static final int CO  = 1 << BIT_CO;
    public static final int J   = 1 << BIT_J;
    public static final int FI  = 1 << BIT_FI;
    public static final int CLR = 1 << BIT_CLR;

    public static final int SIGNAL_COUNT = 17;
    public static final int BUS_DRIVER_MASK = RO | AO | BO | EO | CO;

    public record SignalSpec(
            int signalId,
            String name,
            int bitPosition,
            int mask,
            boolean busDriver,
            String description
    ) {
    }

    public static final List<SignalSpec> ALL_SIGNALS = List.of(
            new SignalSpec(BIT_HLT, "HLT", BIT_HLT, HLT, false, "Halt system clock"),
            new SignalSpec(BIT_MI,  "MI",  BIT_MI,  MI,  false, "Memory Address Register (MAR) latch in from bus"),
            new SignalSpec(BIT_RI,  "RI",  BIT_RI,  RI,  false, "RAM byte at MAR latch in from bus"),
            new SignalSpec(BIT_RO,  "RO",  BIT_RO,  RO,  true,  "RAM byte at MAR drive out to bus"),
            new SignalSpec(BIT_II,  "II",  BIT_II,  II,  false, "Instruction Register (IR) latch opcode in from bus"),
            new SignalSpec(BIT_AI,  "AI",  BIT_AI,  AI,  false, "Register A latch in from bus"),
            new SignalSpec(BIT_AO,  "AO",  BIT_AO,  AO,  true,  "Register A drive out to bus"),
            new SignalSpec(BIT_BI,  "BI",  BIT_BI,  BI,  false, "Register B latch in from bus"),
            new SignalSpec(BIT_BO,  "BO",  BIT_BO,  BO,  true,  "Register B drive out to bus"),
            new SignalSpec(BIT_EO,  "EO",  BIT_EO,  EO,  true,  "ALU result drive out to bus"),
            new SignalSpec(BIT_SU,  "SU",  BIT_SU,  SU,  false, "ALU subtract mode (A - B)"),
            new SignalSpec(BIT_OI,  "OI",  BIT_OI,  OI,  false, "Output Register (outReg) latch in from bus"),
            new SignalSpec(BIT_CE,  "CE",  BIT_CE,  CE,  false, "Program Counter increment by 1"),
            new SignalSpec(BIT_CO,  "CO",  BIT_CO,  CO,  true,  "Program Counter drive out to bus"),
            new SignalSpec(BIT_J,   "J",   BIT_J,   J,   false, "Program Counter jump / latch in from bus"),
            new SignalSpec(BIT_FI,  "FI",  BIT_FI,  FI,  false, "Flags Register latch Zero and Carry from ALU"),
            new SignalSpec(BIT_CLR, "CLR", BIT_CLR, CLR, false, "Clear sequencer ring counter to T0")
    );

    private ControlSignals() {
    }

    public static boolean isBusDriver(int mask) {
        return mask != 0
                && (mask & ~BUS_DRIVER_MASK) == 0
                && Integer.bitCount(mask) == 1;
    }

    public static boolean hasBusContention(int controlWord) {
        return Integer.bitCount(controlWord & BUS_DRIVER_MASK) > 1;
    }

    public static String formatActiveSignals(int controlWord) {
        if (controlWord == 0) {
            return "NONE";
        }
        StringJoiner joiner = new StringJoiner(" | ");
        for (SignalSpec spec : ALL_SIGNALS) {
            if ((controlWord & spec.mask()) != 0) {
                joiner.add(spec.name());
            }
        }
        return joiner.length() == 0 ? "NONE" : joiner.toString();
    }
}

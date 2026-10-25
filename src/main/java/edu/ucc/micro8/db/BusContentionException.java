package edu.ucc.micro8.db;

import java.util.List;

public final class BusContentionException extends RuntimeException {

    private final int opcode;
    private final String mnemonic;
    private final int tState;
    private final int flagState;
    private final List<String> conflictingDrivers;

    public BusContentionException(
            int opcode,
            String mnemonic,
            int tState,
            int flagState,
            List<String> conflictingDrivers
    ) {
        super(String.format(
                "Bus contention on opcode 0x%02X (%s) at T%d [flagState=%d]: multiple bus drivers asserted (%s)",
                opcode & 0xFF,
                mnemonic,
                tState,
                flagState,
                String.join(", ", conflictingDrivers)
        ));
        this.opcode = opcode & 0xFF;
        this.mnemonic = mnemonic;
        this.tState = tState;
        this.flagState = flagState;
        this.conflictingDrivers = List.copyOf(conflictingDrivers);
    }

    public int opcode() {
        return opcode;
    }

    public String mnemonic() {
        return mnemonic;
    }

    public int tState() {
        return tState;
    }

    public int flagState() {
        return flagState;
    }

    public List<String> conflictingDrivers() {
        return conflictingDrivers;
    }
}

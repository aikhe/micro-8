package edu.ucc.micro8.cpu;

public record TraceRow(
        int cycleCount,
        int tState,
        int pc,
        int opcode,
        String mnemonic,
        int busValue,
        int regA,
        int regB,
        int mar,
        boolean zeroFlag,
        boolean carryFlag,
        int outReg,
        int controlWord
) {
    public TraceRow {
        pc = pc & 0xFF;
        opcode = opcode & 0xFF;
        mnemonic = mnemonic == null ? "???" : mnemonic;
        busValue = busValue & 0xFF;
        regA = regA & 0xFF;
        regB = regB & 0xFF;
        mar = mar & 0xFF;
        outReg = outReg & 0xFF;
    }

    public String outDecimal() {
        return Integer.toString(outReg & 0xFF);
    }

    public String outHex() {
        int val = outReg & 0xFF;
        char high = Character.toUpperCase(Character.forDigit((val >>> 4) & 0x0F, 16));
        char low = Character.toUpperCase(Character.forDigit(val & 0x0F, 16));
        return "0x" + high + low;
    }

    public String outAscii() {
        int val = outReg & 0xFF;
        if (val >= 0x20 && val <= 0x7E) {
            return String.valueOf((char) val);
        }
        return ".";
    }
}

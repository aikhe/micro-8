package dev.frtzhahn.micro8.cpu;

import java.util.Arrays;

public record CpuSnapshot(
        int cycleCount,
        int tState,
        int pc,
        int mar,
        int ir,
        int regA,
        int regB,
        int outReg,
        int aluResult,
        boolean zeroFlag,
        boolean carryFlag,
        boolean halted,
        int busValue,
        int controlWord,
        byte[] ramCopy
) {
    public CpuSnapshot {
        pc = pc & 0xFF;
        mar = mar & 0xFF;
        ir = ir & 0xFF;
        regA = regA & 0xFF;
        regB = regB & 0xFF;
        outReg = outReg & 0xFF;
        aluResult = aluResult & 0xFF;
        busValue = busValue & 0xFF;
        ramCopy = ramCopy == null ? new byte[256] : Arrays.copyOf(ramCopy, 256);
    }
}

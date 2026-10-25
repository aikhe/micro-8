package edu.ucc.micro8.cpu;

public final class Alu {

    private static final int CARRY_BIT = 0x100;
    private static final int ZERO_BIT  = 0x200;

    private Alu() {
    }

    public static int computeRawSum(int regA, int regB, boolean subtract) {
        int a = regA & 0xFF;
        int b = regB & 0xFF;
        if (subtract) {
            // hardware 2's complement subtraction: A + (~B) + 1
            return a + ((~b) & 0xFF) + 1;
        }
        return a + b;
    }

    public static int computeResult(int regA, int regB, boolean subtract) {
        return computeRawSum(regA, regB, subtract) & 0xFF;
    }

    public static boolean computeCarry(int regA, int regB, boolean subtract) {
        return (computeRawSum(regA, regB, subtract) & CARRY_BIT) != 0;
    }

    public static boolean computeZero(int regA, int regB, boolean subtract) {
        return computeResult(regA, regB, subtract) == 0;
    }

    public static int evaluatePacked(int regA, int regB, boolean subtract) {
        int raw = computeRawSum(regA, regB, subtract);
        int result = raw & 0xFF;
        int carryBit = raw & CARRY_BIT;
        int zeroBit = (result == 0) ? ZERO_BIT : 0;
        return result | carryBit | zeroBit;
    }

    public static int unpackResult(int packed) {
        return packed & 0xFF;
    }

    public static boolean unpackCarry(int packed) {
        return (packed & CARRY_BIT) != 0;
    }

    public static boolean unpackZero(int packed) {
        return (packed & ZERO_BIT) != 0;
    }
}

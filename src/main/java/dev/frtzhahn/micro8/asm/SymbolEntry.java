package dev.frtzhahn.micro8.asm;

public record SymbolEntry(
        String label,
        int address,
        int definedOnLine
) {
    public SymbolEntry {
        label = label == null ? "" : label;
        address = address & 0xFF;
    }

    public String addressHex() {
        int val = address & 0xFF;
        char high = Character.toUpperCase(Character.forDigit((val >>> 4) & 0x0F, 16));
        char low = Character.toUpperCase(Character.forDigit(val & 0x0F, 16));
        return "0x" + high + low;
    }
}

package dev.frtzhahn.micro8.asm;

import java.util.Arrays;
import java.util.List;

public record AssembledProgram(
        byte[] ramImage,
        List<SymbolEntry> symbolTable,
        int[] addressToSourceLine,
        List<SyntaxError> errors,
        int emittedByteCount
) {
    public AssembledProgram {
        ramImage = ramImage == null ? new byte[256] : Arrays.copyOf(ramImage, 256);
        symbolTable = symbolTable == null ? List.of() : List.copyOf(symbolTable);
        if (addressToSourceLine == null) {
            addressToSourceLine = new int[256];
            Arrays.fill(addressToSourceLine, -1);
        } else {
            addressToSourceLine = Arrays.copyOf(addressToSourceLine, 256);
        }
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean isSuccess() {
        return errors.isEmpty();
    }
}

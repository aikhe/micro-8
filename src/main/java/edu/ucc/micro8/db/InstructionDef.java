package edu.ucc.micro8.db;

public record InstructionDef(
        int opcode,
        String mnemonic,
        boolean hasOperand,
        boolean builtin,
        String description
) {
    public InstructionDef {
        opcode = opcode & 0xFF;
    }
}

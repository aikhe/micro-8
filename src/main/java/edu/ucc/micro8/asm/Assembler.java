package edu.ucc.micro8.asm;

import edu.ucc.micro8.db.InstructionDef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Two-pass assembler targeting the uniform 2-byte MICRO-8 ISA and 256-byte RAM space.
 */
public final class Assembler {

    public static final int RAM_SIZE = 256;

    private final Map<String, InstructionDef> instructionMap = new LinkedHashMap<>();

    private record PendingStatement(
            int lineNumber,
            int address,
            boolean dataDirective,
            InstructionDef instruction,
            String operandToken
    ) {
    }

    public Assembler() {
        this(defaultInstructions());
    }

    public Assembler(Collection<InstructionDef> instructions) {
        updateInstructions(instructions);
    }

    public void updateInstructions(Collection<InstructionDef> instructions) {
        Objects.requireNonNull(instructions, "instructions must not be null");
        instructionMap.clear();
        for (InstructionDef def : instructions) {
            if (def != null && def.mnemonic() != null && !def.mnemonic().isBlank()) {
                instructionMap.put(def.mnemonic().trim().toUpperCase(Locale.ROOT), def);
            }
        }
    }

    public Map<String, InstructionDef> getInstructionMap() {
        return Map.copyOf(instructionMap);
    }

    public AssembledProgram assemble(String source) {
        byte[] ramImage = new byte[RAM_SIZE];
        int[] addressToSourceLine = new int[RAM_SIZE];
        Arrays.fill(addressToSourceLine, -1);

        List<SyntaxError> errors = new ArrayList<>();
        Map<String, SymbolEntry> symbolMap = new LinkedHashMap<>();
        List<PendingStatement> statements = new ArrayList<>();

        if (source == null || source.isBlank()) {
            return new AssembledProgram(ramImage, List.of(), addressToSourceLine, errors, 0);
        }

        String[] lines = source.split("\\R", -1);
        int cursor = 0;

        // Pass 1: parse labels, ORG/DB directives, instruction boundaries, and build symbol table
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String stripped = stripComment(lines[i]).trim();
            if (stripped.isEmpty()) {
                continue;
            }

            String remainder = stripped;
            int colonIdx = remainder.indexOf(':');
            if (colonIdx >= 0) {
                String rawLabel = remainder.substring(0, colonIdx).trim();
                remainder = remainder.substring(colonIdx + 1).trim();

                if (rawLabel.isEmpty() || !isValidIdentifier(rawLabel)) {
                    errors.add(new SyntaxError(lineNumber, rawLabel, "Invalid label name"));
                } else {
                    String upperLabel = rawLabel.toUpperCase(Locale.ROOT);
                    if (isReservedWord(upperLabel)) {
                        errors.add(new SyntaxError(lineNumber, rawLabel, "Label cannot use reserved mnemonic or directive name"));
                    } else if (symbolMap.containsKey(upperLabel)) {
                        SymbolEntry prev = symbolMap.get(upperLabel);
                        errors.add(new SyntaxError(
                                lineNumber,
                                rawLabel,
                                "Duplicate label '" + upperLabel + "' (already defined on line " + prev.definedOnLine() + ")"
                        ));
                    } else if (cursor >= RAM_SIZE) {
                        errors.add(new SyntaxError(lineNumber, rawLabel, "Label address exceeds 256-byte RAM limit (0xFF)"));
                    } else {
                        symbolMap.put(upperLabel, new SymbolEntry(upperLabel, cursor, lineNumber));
                    }
                }
            }

            if (remainder.isEmpty()) {
                continue;
            }

            if (remainder.indexOf(':') >= 0) {
                errors.add(new SyntaxError(lineNumber, remainder, "Multiple colons on a single line are not allowed"));
                continue;
            }

            String[] tokens = remainder.split("[\\s,]+");
            String opToken = tokens[0];
            String upperOp = opToken.toUpperCase(Locale.ROOT);

            if ("ORG".equals(upperOp)) {
                if (tokens.length < 2) {
                    errors.add(new SyntaxError(lineNumber, opToken, "ORG directive requires an address operand (0x00..0xFF)"));
                    continue;
                }
                if (tokens.length > 2) {
                    errors.add(new SyntaxError(lineNumber, tokens[2], "Unexpected extra token after ORG address"));
                    continue;
                }
                Integer orgAddr = parseUnsignedByteLiteral(tokens[1], lineNumber, errors);
                if (orgAddr != null) {
                    cursor = orgAddr;
                }
                continue;
            }

            if ("DB".equals(upperOp)) {
                if (tokens.length < 2) {
                    errors.add(new SyntaxError(lineNumber, opToken, "DB directive requires a byte value or label operand"));
                    continue;
                }
                if (tokens.length > 2) {
                    errors.add(new SyntaxError(lineNumber, tokens[2], "Unexpected extra token after DB operand"));
                    continue;
                }
                if (cursor >= RAM_SIZE) {
                    errors.add(new SyntaxError(lineNumber, opToken, "RAM overflow: DB exceeds 256-byte memory limit (0xFF)"));
                    continue;
                }
                statements.add(new PendingStatement(lineNumber, cursor, true, null, tokens[1]));
                cursor += 1;
                continue;
            }

            InstructionDef instr = instructionMap.get(upperOp);
            if (instr == null) {
                errors.add(new SyntaxError(lineNumber, opToken, "Unknown instruction mnemonic '" + opToken + "'"));
                continue;
            }

            if (instr.hasOperand()) {
                if (tokens.length < 2) {
                    errors.add(new SyntaxError(lineNumber, opToken, "Instruction '" + instr.mnemonic() + "' requires an operand"));
                    continue;
                }
                if (tokens.length > 2) {
                    errors.add(new SyntaxError(lineNumber, tokens[2], "Unexpected extra operand for '" + instr.mnemonic() + "'"));
                    continue;
                }
            } else if (tokens.length > 1) {
                errors.add(new SyntaxError(lineNumber, tokens[1], "Instruction '" + instr.mnemonic() + "' does not take an operand"));
                continue;
            }

            if (cursor + 1 >= RAM_SIZE) {
                errors.add(new SyntaxError(
                        lineNumber,
                        opToken,
                        "RAM overflow: 2-byte instruction '" + instr.mnemonic() + "' at 0x"
                                + Integer.toHexString(cursor).toUpperCase(Locale.ROOT)
                                + " exceeds 256-byte memory limit (0xFF)"
                ));
                cursor += 2;
                continue;
            }

            String operandToken = instr.hasOperand() ? tokens[1] : null;
            statements.add(new PendingStatement(lineNumber, cursor, false, instr, operandToken));
            cursor += 2;
        }

        // Pass 2: resolve operands and emit machine bytes into 256-byte RAM image
        int emittedBytes = 0;
        for (PendingStatement stmt : statements) {
            if (stmt.dataDirective()) {
                Integer val = resolveOperand(stmt.operandToken(), stmt.lineNumber(), symbolMap, errors);
                int byteVal = val == null ? 0 : (val & 0xFF);
                ramImage[stmt.address() & 0xFF] = (byte) byteVal;
                addressToSourceLine[stmt.address() & 0xFF] = stmt.lineNumber();
                emittedBytes += 1;
            } else {
                InstructionDef instr = stmt.instruction();
                int operandByte = 0;
                if (instr.hasOperand()) {
                    Integer resolved = resolveOperand(stmt.operandToken(), stmt.lineNumber(), symbolMap, errors);
                    if (resolved != null) {
                        operandByte = resolved & 0xFF;
                    }
                }
                int addr0 = stmt.address() & 0xFF;
                int addr1 = (stmt.address() + 1) & 0xFF;
                ramImage[addr0] = (byte) (instr.opcode() & 0xFF);
                ramImage[addr1] = (byte) (operandByte & 0xFF);
                addressToSourceLine[addr0] = stmt.lineNumber();
                addressToSourceLine[addr1] = stmt.lineNumber();
                emittedBytes += 2;
            }
        }

        return new AssembledProgram(
                ramImage,
                List.copyOf(symbolMap.values()),
                addressToSourceLine,
                errors,
                emittedBytes
        );
    }

    private Integer resolveOperand(
            String token,
            int lineNumber,
            Map<String, SymbolEntry> symbolMap,
            List<SyntaxError> errors
    ) {
        if ( looksLikeNumericLiteral(token) ) {
            return parseUnsignedByteLiteral(token, lineNumber, errors);
        }
        if (isValidIdentifier(token)) {
            SymbolEntry entry = symbolMap.get(token.toUpperCase(Locale.ROOT));
            if (entry == null) {
                errors.add(new SyntaxError(lineNumber, token, "Undefined label '" + token + "'"));
                return null;
            }
            return entry.address() & 0xFF;
        }
        errors.add(new SyntaxError(lineNumber, token, "Malformed operand '" + token + "'"));
        return null;
    }

    private boolean looksLikeNumericLiteral(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        return Character.isDigit(first) || first == '-' || first == '+' || first == '$';
    }

    private Integer parseUnsignedByteLiteral(String token, int lineNumber, List<SyntaxError> errors) {
        String clean = token.replace("_", "").trim();
        if (clean.isEmpty()) {
            errors.add(new SyntaxError(lineNumber, token, "Empty numeric literal"));
            return null;
        }
        try {
            int value;
            if (clean.startsWith("0x") || clean.startsWith("0X")) {
                value = Integer.parseInt(clean.substring(2), 16);
            } else if (clean.startsWith("$")) {
                value = Integer.parseInt(clean.substring(1), 16);
            } else if (clean.startsWith("0b") || clean.startsWith("0B")) {
                value = Integer.parseInt(clean.substring(2), 2);
            } else {
                value = Integer.parseInt(clean, 10);
            }

            if (value < 0 || value > 255) {
                errors.add(new SyntaxError(
                        lineNumber,
                        token,
                        "Numeric literal out of unsigned 8-bit range (0..255 / 0x00..0xFF): " + token
                ));
                return null;
            }
            return value & 0xFF;
        } catch (NumberFormatException ex) {
            errors.add(new SyntaxError(lineNumber, token, "Invalid numeric literal '" + token + "'"));
            return null;
        }
    }

    private boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private boolean isReservedWord(String upper) {
        return "ORG".equals(upper) || "DB".equals(upper) || instructionMap.containsKey(upper);
    }

    private static String stripComment(String line) {
        int semiIdx = line.indexOf(';');
        int slashIdx = line.indexOf("//");
        int cutIdx = -1;
        if (semiIdx >= 0 && slashIdx >= 0) {
            cutIdx = Math.min(semiIdx, slashIdx);
        } else if (semiIdx >= 0) {
            cutIdx = semiIdx;
        } else if (slashIdx >= 0) {
            cutIdx = slashIdx;
        }
        return cutIdx >= 0 ? line.substring(0, cutIdx) : line;
    }

    public static List<InstructionDef> defaultInstructions() {
        return List.of(
                new InstructionDef(0x00, "NOP", false, true, "No operation"),
                new InstructionDef(0x10, "LDA", true,  true, "Load RAM byte into Register A"),
                new InstructionDef(0x11, "LDI", true,  true, "Load 8-bit immediate into Register A"),
                new InstructionDef(0x20, "ADD", true,  true, "Add RAM byte to Register A"),
                new InstructionDef(0x21, "SUB", true,  true, "Subtract RAM byte from Register A"),
                new InstructionDef(0x30, "STA", true,  true, "Store Register A into RAM"),
                new InstructionDef(0x40, "JMP", true,  true, "Unconditional jump"),
                new InstructionDef(0x41, "JZ",  true,  true, "Jump if Zero flag set"),
                new InstructionDef(0x42, "JC",  true,  true, "Jump if Carry flag set"),
                new InstructionDef(0xE0, "OUT", false, true, "Latch Register A into Output Register"),
                new InstructionDef(0xF0, "HLT", false, true, "Halt system clock")
        );
    }
}

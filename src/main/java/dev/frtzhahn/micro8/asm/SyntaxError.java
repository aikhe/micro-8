package dev.frtzhahn.micro8.asm;

public record SyntaxError(
        int lineNumber,
        String token,
        String message
) {
    public SyntaxError {
        token = token == null ? "" : token;
        message = message == null ? "Syntax error" : message;
    }

    public String format() {
        if (token.isEmpty()) {
            return "Line " + lineNumber + ": " + message;
        }
        return "Line " + lineNumber + " [" + token + "]: " + message;
    }
}

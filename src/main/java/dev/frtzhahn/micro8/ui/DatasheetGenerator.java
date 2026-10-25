package dev.frtzhahn.micro8.ui;

import dev.frtzhahn.micro8.cpu.ControlSignals;
import dev.frtzhahn.micro8.cpu.ControlSignals.SignalSpec;
import dev.frtzhahn.micro8.db.InstructionDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates a self-contained dark-themed HTML ISA and microcode datasheet from SQLite state.
 */
public final class DatasheetGenerator {

    private static final String[] FLAG_LABELS = {
            "Z=0, C=0 (flag 0)",
            "Z=1, C=0 (flag 1)",
            "Z=0, C=1 (flag 2)",
            "Z=1, C=1 (flag 3)"
    };

    private DatasheetGenerator() {
    }

    public static String generateHtml(List<InstructionDef> instructions, int[][][] controlStore) {
        List<InstructionDef> sorted = new ArrayList<>(instructions == null ? List.of() : instructions);
        sorted.sort(Comparator.comparingInt(def -> def.opcode() & 0xFF));

        StringBuilder html = new StringBuilder(8192);
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <title>MICRO-8 ISA Datasheet</title>
                <style>
                    body {
                        background-color: #111318;
                        color: #E2E8F0;
                        font-family: "JetBrains Mono", "Consolas", monospace;
                        font-size: 12px;
                        margin: 14px;
                    }
                    h1 {
                        color: #00E5FF;
                        font-size: 16px;
                        margin: 0 0 6px 0;
                    }
                    h2 {
                        color: #94A3B8;
                        font-size: 13px;
                        margin: 16px 0 8px 0;
                        border-bottom: 1px solid #2A2F3A;
                        padding-bottom: 4px;
                    }
                    .subtitle {
                        color: #94A3B8;
                        margin-bottom: 12px;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        background-color: #1A1D24;
                        border: 1px solid #2A2F3A;
                        margin-bottom: 14px;
                    }
                    th {
                        background-color: #242936;
                        color: #94A3B8;
                        text-align: left;
                        padding: 6px 8px;
                        border-bottom: 1px solid #2A2F3A;
                    }
                    td {
                        padding: 6px 8px;
                        border-bottom: 1px solid #242936;
                        vertical-align: top;
                    }
                    .card {
                        background-color: #1A1D24;
                        border: 1px solid #2A2F3A;
                        border-radius: 6px;
                        padding: 10px 12px;
                        margin-bottom: 12px;
                    }
                    .card-header {
                        font-weight: bold;
                        color: #00E5FF;
                        margin-bottom: 6px;
                    }
                    .badge-builtin {
                        background-color: #1E3A5F;
                        color: #00E5FF;
                        padding: 1px 6px;
                        border-radius: 4px;
                        font-size: 11px;
                    }
                    .badge-custom {
                        background-color: #3B234A;
                        color: #CE93D8;
                        padding: 1px 6px;
                        border-radius: 4px;
                        font-size: 11px;
                    }
                    .sig-driver {
                        display: inline-block;
                        background-color: #082F38;
                        border: 1px solid #00E5FF;
                        color: #00E5FF;
                        padding: 1px 5px;
                        border-radius: 3px;
                        margin-right: 4px;
                        font-weight: bold;
                    }
                    .sig-consumer {
                        display: inline-block;
                        background-color: #0D2E1E;
                        border: 1px solid #00E676;
                        color: #00E676;
                        padding: 1px 5px;
                        border-radius: 3px;
                        margin-right: 4px;
                    }
                    .sig-idle {
                        color: #64748B;
                    }
                    .opcode-hex {
                        color: #FFD54F;
                        font-weight: bold;
                    }
                    .mnemonic {
                        color: #00E5FF;
                        font-weight: bold;
                    }
                </style>
                </head>
                <body>
                <h1>MICRO-8 UNIFORM 2-BYTE ISA DATASHEET</h1>
                <div class="subtitle">Byte 0 = 8-bit Opcode | Byte 1 = 8-bit Operand/Address or 0x00 Padding | 6-Phase Sequencer (T0-T5)</div>
                <h2>Instruction Summary Table</h2>
                <table>
                    <thead>
                        <tr>
                            <th>Opcode</th>
                            <th>Mnemonic</th>
                            <th>2-Byte Encoding</th>
                            <th>Type</th>
                            <th>Description</th>
                        </tr>
                    </thead>
                    <tbody>
                """);

        for (InstructionDef def : sorted) {
            int op = def.opcode() & 0xFF;
            String hexOp = formatHex2(op);
            String formatStr = def.hasOperand() ? "Opcode + Operand (2B)" : "Opcode + 0x00 (2B)";
            String badgeClass = def.builtin() ? "badge-builtin" : "badge-custom";
            String badgeText = def.builtin() ? "Built-in" : "Custom";

            html.append("<tr>")
                    .append("<td class=\"opcode-hex\">").append(hexOp).append("</td>")
                    .append("<td class=\"mnemonic\">").append(escapeHtml(def.mnemonic())).append("</td>")
                    .append("<td>").append(formatStr).append("</td>")
                    .append("<td><span class=\"").append(badgeClass).append("\">").append(badgeText).append("</span></td>")
                    .append("<td>").append(escapeHtml(def.description())).append("</td>")
                    .append("</tr>\n");
        }

        html.append("""
                    </tbody>
                </table>
                <h2>Microcode Control-Store Breakdown (T0 - T5)</h2>
                """);

        for (InstructionDef def : sorted) {
            int op = def.opcode() & 0xFF;
            String hexOp = formatHex2(op);
            String badgeClass = def.builtin() ? "badge-builtin" : "badge-custom";
            String badgeText = def.builtin() ? "Built-in" : "Custom";

            html.append("<div class=\"card\">\n")
                    .append("<div class=\"card-header\">")
                    .append(hexOp).append(" - ").append(escapeHtml(def.mnemonic()))
                    .append(" <span class=\"").append(badgeClass).append("\">").append(badgeText).append("</span>")
                    .append(" - ").append(escapeHtml(def.description()))
                    .append("</div>\n")
                    .append("<table><thead><tr><th style=\"width:70px;\">T-State</th><th>Control Signals (32-Bit Control Word)</th></tr></thead><tbody>\n");

            for (int t = 0; t < 6; t++) {
                int w0 = getWord(controlStore, op, t, 0);
                int w1 = getWord(controlStore, op, t, 1);
                int w2 = getWord(controlStore, op, t, 2);
                int w3 = getWord(controlStore, op, t, 3);
                boolean uniformFlags = (w0 == w1) && (w0 == w2) && (w0 == w3);

                html.append("<tr><td><strong>T").append(t).append("</strong></td><td>");
                if (uniformFlags) {
                    html.append(formatSignalBadgesHtml(w0));
                } else {
                    for (int flag = 0; flag < 4; flag++) {
                        int word = getWord(controlStore, op, t, flag);
                        html.append("<div><strong>").append(FLAG_LABELS[flag]).append(":</strong> ")
                                .append(formatSignalBadgesHtml(word))
                                .append("</div>");
                    }
                }
                html.append("</td></tr>\n");
            }
            html.append("</tbody></table></div>\n");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static int getWord(int[][][] controlStore, int opcode, int tState, int flagState) {
        if (controlStore == null
                || opcode < 0 || opcode >= controlStore.length
                || tState < 0 || tState >= controlStore[opcode].length
                || flagState < 0 || flagState >= controlStore[opcode][tState].length) {
            return 0;
        }
        return controlStore[opcode][tState][flagState];
    }

    private static String formatSignalBadgesHtml(int controlWord) {
        if (controlWord == 0) {
            return "<span class=\"sig-idle\">- (0x00000000)</span>";
        }
        StringBuilder sb = new StringBuilder();
        for (SignalSpec spec : ControlSignals.ALL_SIGNALS) {
            if ((controlWord & spec.mask()) != 0) {
                String css = spec.busDriver() ? "sig-driver" : "sig-consumer";
                sb.append("<span class=\"").append(css).append("\">")
                        .append(spec.name())
                        .append("</span>");
            }
        }
        sb.append("<span class=\"sig-idle\">(")
                .append(escapeHtml(ControlSignals.formatActiveSignals(controlWord)))
                .append(")</span>");
        return sb.toString();
    }

    private static String formatHex2(int value) {
        int v = value & 0xFF;
        char high = Character.toUpperCase(Character.forDigit((v >>> 4) & 0x0F, 16));
        char low = Character.toUpperCase(Character.forDigit(v & 0x0F, 16));
        return "0x" + high + low;
    }

    private static String escapeHtml(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

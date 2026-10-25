package dev.frtzhahn.micro8.ui;

import dev.frtzhahn.micro8.cpu.ControlSignals;
import dev.frtzhahn.micro8.cpu.ControlSignals.SignalSpec;
import dev.frtzhahn.micro8.cpu.CpuSnapshot;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import static dev.frtzhahn.micro8.cpu.ControlSignals.AI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.AO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.BI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.BO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.BUS_DRIVER_MASK;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CE;
import static dev.frtzhahn.micro8.cpu.ControlSignals.CO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.EO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.FI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.II;
import static dev.frtzhahn.micro8.cpu.ControlSignals.J;
import static dev.frtzhahn.micro8.cpu.ControlSignals.MI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.OI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.RI;
import static dev.frtzhahn.micro8.cpu.ControlSignals.RO;
import static dev.frtzhahn.micro8.cpu.ControlSignals.SU;

/**
 * Vector canvas rendering the MICRO-8 system bus, registers, ALU, T0-T5 sequencer, and 17 control wires at 60 FPS.
 */
public final class CpuSchematicView extends Pane {

    private static final Color BG_SURFACE       = Color.web("#111318");
    private static final Color CARD_FILL        = Color.web("#1A1D24");
    private static final Color CARD_BORDER      = Color.web("#2A2F3A");
    private static final Color WIRE_IDLE        = Color.web("#333A48");
    private static final Color WIRE_DRIVER      = Color.web("#00E5FF");
    private static final Color WIRE_CONSUMER    = Color.web("#00E676");
    private static final Color TSTATE_ACTIVE    = Color.web("#2979FF");
    private static final Color TEXT_PRIMARY     = Color.web("#E2E8F0");
    private static final Color TEXT_MUTED       = Color.web("#94A3B8");
    private static final Color TEXT_GOLD        = Color.web("#FFD54F");

    private static final Font FONT_TITLE = Font.font("Monospaced", FontWeight.BOLD, 12);
    private static final Font FONT_VALUE = Font.font("Monospaced", FontWeight.BOLD, 13);
    private static final Font FONT_SMALL = Font.font("Monospaced", FontWeight.NORMAL, 11);

    private final Canvas canvas = new Canvas(600, 460);
    private CpuSnapshot currentSnapshot;
    private String currentMnemonic = "NOP";

    public CpuSchematicView() {
        getChildren().add(canvas);
        widthProperty().addListener((obs, oldVal, newVal) -> resizeAndRedraw());
        heightProperty().addListener((obs, oldVal, newVal) -> resizeAndRedraw());
    }

    public void updateFromSnapshot(CpuSnapshot snapshot, String mnemonic) {
        this.currentSnapshot = snapshot;
        this.currentMnemonic = (mnemonic == null || mnemonic.isBlank()) ? "???" : mnemonic;
        draw();
    }

    private void resizeAndRedraw() {
        double w = Math.max(420.0, getWidth());
        double h = Math.max(360.0, getHeight());
        canvas.setWidth(w);
        canvas.setHeight(h);
        draw();
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setFill(BG_SURFACE);
        gc.fillRoundRect(0, 0, w, h, 8, 8);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(0.5, 0.5, w - 1, h - 1, 8, 8);

        int ctrl = currentSnapshot == null ? 0 : currentSnapshot.controlWord();
        int busVal = currentSnapshot == null ? 0 : currentSnapshot.busValue();
        int pc = currentSnapshot == null ? 0 : currentSnapshot.pc();
        int mar = currentSnapshot == null ? 0 : currentSnapshot.mar();
        int ramAtMar = (currentSnapshot == null) ? 0 : Byte.toUnsignedInt(currentSnapshot.ramCopy()[mar & 0xFF]);
        int ir = currentSnapshot == null ? 0 : currentSnapshot.ir();
        int regA = currentSnapshot == null ? 0 : currentSnapshot.regA();
        int regB = currentSnapshot == null ? 0 : currentSnapshot.regB();
        int alu = currentSnapshot == null ? 0 : currentSnapshot.aluResult();
        int outReg = currentSnapshot == null ? 0 : currentSnapshot.outReg();
        boolean zFlag = currentSnapshot != null && currentSnapshot.zeroFlag();
        boolean cFlag = currentSnapshot != null && currentSnapshot.carryFlag();
        int tState = currentSnapshot == null ? 0 : currentSnapshot.tState();

        boolean busDriven = (ctrl & BUS_DRIVER_MASK) != 0;
        double busX = w * 0.50;
        double topY = 44.0;
        double busBottomY = h - 98.0;

        // System Bus Header & Vertical Spine
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(FONT_TITLE);
        gc.setFill(busDriven ? WIRE_DRIVER : TEXT_PRIMARY);
        gc.fillText(String.format("8-BIT SYSTEM BUS [%s] (%03d)", hex2(busVal), busVal), busX, 24.0);

        gc.setStroke(busDriven ? WIRE_DRIVER : WIRE_IDLE);
        gc.setLineWidth(busDriven ? 4.0 : 2.5);
        gc.strokeLine(busX - 5, topY, busX - 5, busBottomY);
        gc.strokeLine(busX + 5, topY, busX + 5, busBottomY);

        double blockW = Math.min(185.0, (w - 90.0) * 0.40);
        double blockH = Math.min(62.0, (busBottomY - topY - 24.0) / 4.2);
        double leftX = 18.0;
        double rightX = w - blockW - 18.0;
        double rowGap = (busBottomY - topY - blockH * 4) / 3.0;

        // Left Column Blocks: PC, MAR, RAM 256B, IR
        drawBlock(gc, leftX, topY, blockW, blockH, busX - 5, true,
                "PC (Program Counter)",
                String.format("[%s] (%03d)", hex2(pc), pc),
                "CO | CE | J",
                (ctrl & CO) != 0,
                (ctrl & (CE | J)) != 0);

        drawBlock(gc, leftX, topY + (blockH + rowGap), blockW, blockH, busX - 5, true,
                "MAR (Address Reg)",
                String.format("[%s] (%03d)", hex2(mar), mar),
                "MI",
                false,
                (ctrl & MI) != 0);

        drawBlock(gc, leftX, topY + 2 * (blockH + rowGap), blockW, blockH, busX - 5, true,
                "RAM 256B @ MAR",
                String.format("[%s] (%03d)", hex2(ramAtMar), ramAtMar),
                "RO | RI",
                (ctrl & RO) != 0,
                (ctrl & RI) != 0);

        drawBlock(gc, leftX, topY + 3 * (blockH + rowGap), blockW, blockH, busX - 5, true,
                "IR (Opcode Only)",
                String.format("[%s] %s", hex2(ir), currentMnemonic),
                "II (No IO)",
                false,
                (ctrl & II) != 0);

        // Right Column Blocks: REG A, ALU, REG B, OUT
        drawBlock(gc, rightX, topY, blockW, blockH, busX + 5, false,
                "REG A (Accumulator)",
                String.format("[%s] (%03d)", hex2(regA), regA),
                "AO | AI",
                (ctrl & AO) != 0,
                (ctrl & AI) != 0);

        String aluMode = (ctrl & SU) != 0 ? "SUB" : "ADD";
        drawBlock(gc, rightX, topY + (blockH + rowGap), blockW, blockH, busX + 5, false,
                "ALU (" + aluMode + ")",
                String.format("[%s] Z:%d C:%d", hex2(alu), zFlag ? 1 : 0, cFlag ? 1 : 0),
                "EO | SU | FI",
                (ctrl & EO) != 0,
                (ctrl & (SU | FI)) != 0);

        drawBlock(gc, rightX, topY + 2 * (blockH + rowGap), blockW, blockH, busX + 5, false,
                "REG B (Operand)",
                String.format("[%s] (%03d)", hex2(regB), regB),
                "BO | BI",
                (ctrl & BO) != 0,
                (ctrl & BI) != 0);

        drawBlock(gc, rightX, topY + 3 * (blockH + rowGap), blockW, blockH, busX + 5, false,
                "OUT (Output Reg)",
                String.format("[%s] (%03d)", hex2(outReg), outReg),
                "OI",
                false,
                (ctrl & OI) != 0);

        // Sequencer T0..T5 Row
        double seqY = h - 72.0;
        drawSequencerRow(gc, w, seqY, tState);

        // 17 Control Wires Strip
        double wiresY = h - 34.0;
        drawControlWiresRow(gc, w, wiresY, ctrl);
    }

    private void drawBlock(
            GraphicsContext gc,
            double x,
            double y,
            double w,
            double h,
            double busAnchorX,
            boolean leftSide,
            String title,
            String valueText,
            String signalsText,
            boolean activeDriver,
            boolean activeConsumer
    ) {
        double midY = y + h * 0.5;
        double wireStartX = leftSide ? (x + w) : x;

        Color accent = activeDriver ? WIRE_DRIVER : (activeConsumer ? WIRE_CONSUMER : WIRE_IDLE);
        gc.setStroke(accent);
        gc.setLineWidth((activeDriver || activeConsumer) ? 2.8 : 1.4);
        gc.strokeLine(wireStartX, midY, busAnchorX, midY);

        gc.setFill(CARD_FILL);
        gc.fillRoundRect(x, y, w, h, 6, 6);
        gc.setStroke((activeDriver || activeConsumer) ? accent : CARD_BORDER);
        gc.setLineWidth((activeDriver || activeConsumer) ? 2.0 : 1.0);
        gc.strokeRoundRect(x, y, w, h, 6, 6);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(FONT_SMALL);
        gc.setFill(TEXT_MUTED);
        gc.fillText(title, x + 8, y + 16);

        gc.setFont(FONT_VALUE);
        gc.setFill(activeDriver ? WIRE_DRIVER : (activeConsumer ? WIRE_CONSUMER : TEXT_PRIMARY));
        gc.fillText(valueText, x + 8, y + 35);

        gc.setFont(FONT_SMALL);
        gc.setFill(TEXT_GOLD);
        gc.fillText(signalsText, x + 8, y + 52);
    }

    private void drawSequencerRow(GraphicsContext gc, double width, double y, int activeTState) {
        double pillW = 46.0;
        double pillH = 22.0;
        double gap = 8.0;
        double totalW = 6 * pillW + 5 * gap;
        double startX = (width - totalW) * 0.5;

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(FONT_SMALL);

        for (int t = 0; t < 6; t++) {
            double px = startX + t * (pillW + gap);
            boolean active = (t == activeTState);
            gc.setFill(active ? TSTATE_ACTIVE : CARD_FILL);
            gc.fillRoundRect(px, y, pillW, pillH, 5, 5);
            gc.setStroke(active ? WIRE_DRIVER : CARD_BORDER);
            gc.setLineWidth(active ? 1.6 : 1.0);
            gc.strokeRoundRect(px, y, pillW, pillH, 5, 5);

            gc.setFill(active ? Color.WHITE : TEXT_MUTED);
            gc.fillText("T" + t, px + pillW * 0.5, y + 15.0);
        }
    }

    private void drawControlWiresRow(GraphicsContext gc, double width, double y, int ctrl) {
        int count = ControlSignals.ALL_SIGNALS.size();
        double availW = width - 24.0;
        double gap = 3.0;
        double badgeW = Math.max(22.0, (availW - (count - 1) * gap) / count);
        double badgeH = 20.0;
        double startX = (width - (count * badgeW + (count - 1) * gap)) * 0.5;

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(FONT_SMALL);

        for (int i = 0; i < count; i++) {
            SignalSpec spec = ControlSignals.ALL_SIGNALS.get(i);
            double bx = startX + i * (badgeW + gap);
            boolean active = (ctrl & spec.mask()) != 0;
            Color activeColor = spec.busDriver() ? WIRE_DRIVER : WIRE_CONSUMER;

            gc.setFill(active ? Color.web("#162936") : CARD_FILL);
            gc.fillRoundRect(bx, y, badgeW, badgeH, 4, 4);
            gc.setStroke(active ? activeColor : WIRE_IDLE);
            gc.setLineWidth(active ? 1.6 : 1.0);
            gc.strokeRoundRect(bx, y, badgeW, badgeH, 4, 4);

            gc.setFill(active ? activeColor : TEXT_MUTED);
            gc.fillText(spec.name(), bx + badgeW * 0.5, y + 14.0);
        }
    }

    private static String hex2(int value) {
        int v = value & 0xFF;
        char high = Character.toUpperCase(Character.forDigit((v >>> 4) & 0x0F, 16));
        char low = Character.toUpperCase(Character.forDigit(v & 0x0F, 16));
        return "0x" + high + low;
    }
}

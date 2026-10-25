package edu.ucc.micro8.ui;

import edu.ucc.micro8.asm.AssembledProgram;
import edu.ucc.micro8.asm.Assembler;
import edu.ucc.micro8.asm.SymbolEntry;
import edu.ucc.micro8.asm.SyntaxError;
import edu.ucc.micro8.cpu.CpuClockRunner;
import edu.ucc.micro8.cpu.CpuCore;
import edu.ucc.micro8.cpu.CpuSnapshot;
import edu.ucc.micro8.cpu.TraceRow;
import edu.ucc.micro8.db.DatabaseManager;
import edu.ucc.micro8.db.IsaRepository;
import javafx.animation.AnimationTimer;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainWorkbenchController {

    private static final int MAX_TRACE_DRAIN_PER_FRAME = 64;
    private static final int MAX_TRACE_TABLE_ROWS = 512;

    private static final String DEFAULT_SAMPLE_PROGRAM = """
            ; MICRO-8 Sample Program: Add two unsigned bytes -> '*' (0x2A / 42)
            START:
                LDA NUM_A
                ADD NUM_B
                OUT
                HLT

                ORG 0xF0
            NUM_A:
                DB 24
            NUM_B:
                DB 18
            """;

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?<COMMENT>;[^\\r\\n]*|//[^\\r\\n]*)"
                    + "|(?<LABEL>\\b[A-Za-z_][A-Za-z0-9_]*:)"
                    + "|(?<DIRECTIVE>\\b(?i:ORG|DB)\\b)"
                    + "|(?<NUMBER>\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|\\$[0-9A-Fa-f]+|\\d+)\\b)"
                    + "|(?<WORD>\\b[A-Za-z_][A-Za-z0-9_]*\\b)"
    );

    @FXML
    private Button btnAssemble;

    @FXML
    private Button btnRun;

    @FXML
    private Button btnPause;

    @FXML
    private Button btnStepMicro;

    @FXML
    private Button btnStepMacro;

    @FXML
    private Button btnUndoMicro;

    @FXML
    private Button btnUndoMacro;

    @FXML
    private Button btnReset;

    @FXML
    private Slider sldClockHz;

    @FXML
    private Label lblClockHz;

    @FXML
    private Label lblCycleCount;

    @FXML
    private Button btnSnapshots;

    @FXML
    private Button btnHelpOnboarding;

    @FXML
    private StackPane paneCodeHost;

    @FXML
    private TableView<SymbolEntry> tblSymbolTable;

    @FXML
    private Label lblAsmDiagnostics;

    @FXML
    private StackPane paneSchematicHost;

    @FXML
    private Label lblBusValue;

    @FXML
    private Label lblPc;

    @FXML
    private Label lblRegA;

    @FXML
    private Label lblMar;

    @FXML
    private Label lblAlu;

    @FXML
    private Label lblFlags;

    @FXML
    private Label lblRegB;

    @FXML
    private Label lblIr;

    @FXML
    private SevenSegmentCanvas segOutHigh;

    @FXML
    private SevenSegmentCanvas segOutLow;

    @FXML
    private Label lblOutReg;

    @FXML
    private StackPane paneBinaryLedsHost;

    @FXML
    private StackPane paneRamGridHost;

    @FXML
    private Label lblTState;

    @FXML
    private Label lblMicrocodeValidation;

    @FXML
    private Button btnSaveMicrocode;

    @FXML
    private Button btnResetDefaultIsa;

    @FXML
    private Button btnRefreshDatasheet;

    @FXML
    private Button btnRunLabGrader;

    @FXML
    private Label lblLabReport;

    @FXML
    private Button btnClearRam;

    @FXML
    private Button btnLoadHex;

    @FXML
    private TableView<TraceRow> tblTrace;

    @FXML
    private Button btnClearTrace;

    private CodeArea codeAsmEditor;
    private CpuSchematicView schematicView;
    private RamHexGridView ramGridView;
    private final Circle[] binaryLedCircles = new Circle[8];
    private final ObservableList<TraceRow> traceItems = FXCollections.observableArrayList();
    private AnimationTimer uiTimer;

    private DatabaseManager dbManager;
    private IsaRepository isaRepository;
    private final CpuCore cpuCore = new CpuCore();
    private final CpuClockRunner clockRunner = new CpuClockRunner(cpuCore);
    private final Assembler assembler = new Assembler();

    private byte[] lastAssembledRam = new byte[256];
    private boolean programAssembled;
    private int[] addressToSourceLine = new int[256];
    private int highlightedParagraph = -1;

    @FXML
    public void initialize() {
        Arrays.fill(addressToSourceLine, -1);

        if (sldClockHz != null && lblClockHz != null) {
            sldClockHz.valueProperty().addListener((obs, oldVal, newVal) -> {
                int hz = Math.max(1, newVal.intValue());
                lblClockHz.setText(hz + " Hz");
                clockRunner.setFrequencyHz(hz);
            });
        }

        initSymbolTable();
        initCodeEditor();
        initBinaryLeds();
        initRamHexGrid();
        initTraceTable();
        initSchematicView();
        startUiRefreshTimer();
        syncTransportControls();
    }

    private void initSymbolTable() {
        if (tblSymbolTable == null) {
            return;
        }
        TableColumn<SymbolEntry, String> colLabel = new TableColumn<>("Label");
        colLabel.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().label()));
        colLabel.setPrefWidth(150.0);

        TableColumn<SymbolEntry, String> colAddr = new TableColumn<>("Address");
        colAddr.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().addressHex()));
        colAddr.setPrefWidth(100.0);

        tblSymbolTable.getColumns().setAll(List.of(colLabel, colAddr));
    }

    private void initCodeEditor() {
        if (paneCodeHost == null) {
            return;
        }
        codeAsmEditor = new CodeArea();
        codeAsmEditor.getStyleClass().add("code-editor");
        codeAsmEditor.setParagraphGraphicFactory(LineNumberFactory.get(codeAsmEditor));
        codeAsmEditor.textProperty().addListener((obs, oldText, newText) ->
                codeAsmEditor.setStyleSpans(0, computeHighlighting(newText))
        );
        codeAsmEditor.replaceText(DEFAULT_SAMPLE_PROGRAM);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeAsmEditor);
        paneCodeHost.getChildren().setAll(scrollPane);
    }

    private void initBinaryLeds() {
        if (paneBinaryLedsHost == null) {
            return;
        }
        HBox strip = new HBox(10.0);
        strip.setAlignment(Pos.CENTER_LEFT);
        for (int bit = 7; bit >= 0; bit--) {
            Circle led = new Circle(8.0);
            led.getStyleClass().addAll("binary-led-circle", "led-off");
            binaryLedCircles[bit] = led;

            Label bitLbl = new Label("B" + bit);
            bitLbl.getStyleClass().add("section-header");

            VBox col = new VBox(2.0, led, bitLbl);
            col.setAlignment(Pos.CENTER);
            strip.getChildren().add(col);
        }
        paneBinaryLedsHost.getChildren().setAll(strip);
    }

    private void initRamHexGrid() {
        if (paneRamGridHost == null) {
            return;
        }
        ramGridView = new RamHexGridView();
        ramGridView.setCellPokeHandler((addr, val) -> {
            if (clockRunner.isRunning()) {
                return;
            }
            CpuSnapshot snap;
            synchronized (cpuCore) {
                cpuCore.writeRam(addr, val);
                snap = cpuCore.createSnapshot();
            }
            renderSnapshot(snap);
        });
        paneRamGridHost.getChildren().setAll(ramGridView);
    }

    private void initTraceTable() {
        if (tblTrace == null) {
            return;
        }
        TableColumn<TraceRow, String> colCycle = new TableColumn<>("Cycle");
        colCycle.setCellValueFactory(c -> new SimpleStringProperty(String.format("%05d", c.getValue().cycleCount())));
        colCycle.setPrefWidth(72.0);

        TableColumn<TraceRow, String> colTState = new TableColumn<>("T-State");
        colTState.setCellValueFactory(c -> new SimpleStringProperty("T" + c.getValue().tState()));
        colTState.setPrefWidth(64.0);

        TableColumn<TraceRow, String> colPc = new TableColumn<>("PC");
        colPc.setCellValueFactory(c -> new SimpleStringProperty(hex2(c.getValue().pc())));
        colPc.setPrefWidth(64.0);

        TableColumn<TraceRow, String> colOp = new TableColumn<>("Opcode/Mnemonic");
        colOp.setCellValueFactory(c -> new SimpleStringProperty(
                hex2(c.getValue().opcode()) + " (" + c.getValue().mnemonic() + ")"
        ));
        colOp.setPrefWidth(135.0);

        TableColumn<TraceRow, String> colBus = new TableColumn<>("Bus");
        colBus.setCellValueFactory(c -> new SimpleStringProperty(hex2(c.getValue().busValue())));
        colBus.setPrefWidth(64.0);

        TableColumn<TraceRow, String> colRegA = new TableColumn<>("RegA");
        colRegA.setCellValueFactory(c -> new SimpleStringProperty(hex2(c.getValue().regA())));
        colRegA.setPrefWidth(64.0);

        TableColumn<TraceRow, String> colRegB = new TableColumn<>("RegB");
        colRegB.setCellValueFactory(c -> new SimpleStringProperty(hex2(c.getValue().regB())));
        colRegB.setPrefWidth(64.0);

        TableColumn<TraceRow, String> colMar = new TableColumn<>("MAR");
        colMar.setCellValueFactory(c -> new SimpleStringProperty(hex2(c.getValue().mar())));
        colMar.setPrefWidth(64.0);

        TableColumn<TraceRow, String> colFlags = new TableColumn<>("Flags");
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                "Z" + (c.getValue().zeroFlag() ? 1 : 0) + " C" + (c.getValue().carryFlag() ? 1 : 0)
        ));
        colFlags.setPrefWidth(72.0);

        TableColumn<TraceRow, String> colOutDec = new TableColumn<>("Out (Dec)");
        colOutDec.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().outDecimal()));
        colOutDec.setPrefWidth(85.0);

        TableColumn<TraceRow, String> colOutHex = new TableColumn<>("Out (Hex)");
        colOutHex.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().outHex()));
        colOutHex.setPrefWidth(85.0);

        TableColumn<TraceRow, String> colOutAsc = new TableColumn<>("Out (ASC)");
        colOutAsc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().outAscii()));
        colOutAsc.setPrefWidth(85.0);

        tblTrace.getColumns().setAll(List.of(
                colCycle, colTState, colPc, colOp, colBus, colRegA, colRegB, colMar, colFlags, colOutDec, colOutHex, colOutAsc
        ));
        tblTrace.setItems(traceItems);
    }

    private void initSchematicView() {
        if (paneSchematicHost == null) {
            return;
        }
        schematicView = new CpuSchematicView();
        paneSchematicHost.getChildren().setAll(schematicView);
        synchronized (cpuCore) {
            renderSnapshot(cpuCore.createSnapshot());
        }
    }

    private void startUiRefreshTimer() {
        uiTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                CpuSnapshot snap = cpuCore.consumeLatestSnapshot();
                if (snap != null) {
                    renderSnapshot(snap);
                }
                drainTraceQueueBounded();
                syncTransportControls();
            }
        };
        uiTimer.start();
    }

    private void drainTraceQueueBounded() {
        int drained = 0;
        while (drained < MAX_TRACE_DRAIN_PER_FRAME) {
            TraceRow row = cpuCore.getTraceQueue().poll();
            if (row == null) {
                break;
            }
            traceItems.add(row);
            drained++;
        }
        if (drained > 0) {
            if (traceItems.size() > MAX_TRACE_TABLE_ROWS) {
                traceItems.remove(0, traceItems.size() - MAX_TRACE_TABLE_ROWS);
            }
            if (tblTrace != null && !traceItems.isEmpty()) {
                tblTrace.scrollTo(traceItems.size() - 1);
            }
        }
    }

    @FXML
    public void handleAssemble() {
        clockRunner.pause();
        if (codeAsmEditor == null) {
            return;
        }
        reloadIsaDefinitionsIfAvailable();

        AssembledProgram program = assembler.assemble(codeAsmEditor.getText());
        if (!program.isSuccess()) {
            SyntaxError first = program.errors().getFirst();
            showDiagnosticsError(first.format() + " (" + program.errors().size() + " error(s))");
            highlightEditorLine(first.lineNumber());
            syncTransportControls();
            return;
        }

        this.addressToSourceLine = program.addressToSourceLine();
        this.lastAssembledRam = Arrays.copyOf(program.ramImage(), 256);
        this.programAssembled = true;

        CpuSnapshot snap;
        synchronized (cpuCore) {
            cpuCore.wipeCleanSlate();
            cpuCore.loadProgramBytes(0, lastAssembledRam);
            snap = cpuCore.createSnapshot();
        }
        traceItems.clear();

        if (tblSymbolTable != null) {
            tblSymbolTable.setItems(FXCollections.observableArrayList(program.symbolTable()));
        }

        showDiagnosticsSuccess("Ready. Assembled " + program.emittedByteCount() + "B (" + program.symbolTable().size() + " symbol(s)).");
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleRun() {
        if (!programAssembled) {
            handleAssemble();
            if (!programAssembled) {
                return;
            }
        }
        clockRunner.start();
        syncTransportControls();
    }

    @FXML
    public void handlePause() {
        clockRunner.pause();
        syncTransportControls();
    }

    @FXML
    public void handleStepMicro() {
        clockRunner.pause();
        if (!programAssembled) {
            handleAssemble();
            if (!programAssembled) {
                return;
            }
        }
        CpuSnapshot snap;
        synchronized (cpuCore) {
            cpuCore.stepMicro();
            snap = cpuCore.createSnapshot();
        }
        drainTraceQueueBounded();
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleStepMacro() {
        clockRunner.pause();
        if (!programAssembled) {
            handleAssemble();
            if (!programAssembled) {
                return;
            }
        }
        CpuSnapshot snap;
        synchronized (cpuCore) {
            cpuCore.stepMacro();
            snap = cpuCore.createSnapshot();
        }
        drainTraceQueueBounded();
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleUndoMicro() {
        clockRunner.pause();
        drainTraceQueueBounded();
        CpuSnapshot snap;
        int newCycle;
        synchronized (cpuCore) {
            cpuCore.undoMicroStep();
            newCycle = cpuCore.getCycleCount();
            snap = cpuCore.createSnapshot();
        }
        RamHexGridView.pruneTraceRowsAfterCycle(traceItems, newCycle);
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleUndoMacro() {
        clockRunner.pause();
        drainTraceQueueBounded();
        CpuSnapshot snap;
        int newCycle;
        synchronized (cpuCore) {
            cpuCore.undoMacroStep();
            newCycle = cpuCore.getCycleCount();
            snap = cpuCore.createSnapshot();
        }
        RamHexGridView.pruneTraceRowsAfterCycle(traceItems, newCycle);
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleReset() {
        clockRunner.pause();
        CpuSnapshot snap;
        synchronized (cpuCore) {
            cpuCore.wipeCleanSlate();
            if (programAssembled) {
                cpuCore.loadProgramBytes(0, lastAssembledRam);
            }
            snap = cpuCore.createSnapshot();
        }
        traceItems.clear();
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleClearRam() {
        clockRunner.pause();
        CpuSnapshot snap;
        synchronized (cpuCore) {
            cpuCore.wipeCleanSlate();
            snap = cpuCore.createSnapshot();
        }
        Arrays.fill(lastAssembledRam, (byte) 0);
        programAssembled = false;
        traceItems.clear();
        showDiagnosticsSuccess("Cleared all 256 bytes of RAM and reset registers.");
        renderSnapshot(snap);
        syncTransportControls();
    }

    @FXML
    public void handleLoadHex() {
        clockRunner.pause();
        TextInputDialog dialog = new TextInputDialog("10 F0 20 F1 E0 00 F0 00");
        dialog.setTitle("Load Hex Bytes into RAM");
        dialog.setHeaderText("Enter space-separated hex bytes (00..FF) starting at 0x00");
        dialog.setContentText("Hex bytes:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(raw -> {
            try {
                byte[] parsedRam = RamHexGridView.parseHexDump(raw);
                this.lastAssembledRam = Arrays.copyOf(parsedRam, 256);
                this.programAssembled = true;
                CpuSnapshot snap;
                synchronized (cpuCore) {
                    cpuCore.wipeCleanSlate();
                    cpuCore.loadProgramBytes(0, lastAssembledRam);
                    snap = cpuCore.createSnapshot();
                }
                traceItems.clear();
                showDiagnosticsSuccess("Loaded raw hex image into RAM.");
                renderSnapshot(snap);
                syncTransportControls();
            } catch (IllegalArgumentException ex) {
                showDiagnosticsError("Hex load error: " + ex.getMessage());
            }
        });
    }

    @FXML
    public void handleClearTrace() {
        cpuCore.getTraceQueue().clear();
        traceItems.clear();
    }

    private void renderSnapshot(CpuSnapshot snap) {
        if (snap == null) {
            return;
        }
        if (lblCycleCount != null) {
            lblCycleCount.setText(String.format("%05d", snap.cycleCount()));
        }
        if (lblBusValue != null) {
            lblBusValue.setText(hex2(snap.busValue()));
        }
        if (lblTState != null) {
            lblTState.setText("T" + snap.tState());
        }
        if (lblFlags != null) {
            lblFlags.setText("Z:" + (snap.zeroFlag() ? 1 : 0) + " C:" + (snap.carryFlag() ? 1 : 0));
        }
        if (lblPc != null) {
            lblPc.setText(hex2(snap.pc()));
        }
        if (lblMar != null) {
            lblMar.setText(hex2(snap.mar()));
        }
        if (lblIr != null) {
            lblIr.setText(hex2(snap.ir()));
        }
        if (lblRegA != null) {
            lblRegA.setText(hex2(snap.regA()));
        }
        if (lblRegB != null) {
            lblRegB.setText(hex2(snap.regB()));
        }
        if (lblAlu != null) {
            lblAlu.setText(hex2(snap.aluResult()));
        }

        int out = snap.outReg();
        if (segOutHigh != null) {
            segOutHigh.setHexNibble(SevenSegmentCanvas.extractHighNibble(out));
        }
        if (segOutLow != null) {
            segOutLow.setHexNibble(SevenSegmentCanvas.extractLowNibble(out));
        }
        if (lblOutReg != null) {
            String asc = (out >= 0x20 && out <= 0x7E) ? ("'" + (char) out + "'") : "'.'";
            lblOutReg.setText(String.format("Dec: %03d | Hex: %s | ASC: %s", out, hex2(out), asc));
        }

        for (int bit = 0; bit < 8; bit++) {
            Circle led = binaryLedCircles[bit];
            if (led != null) {
                boolean bitOn = SevenSegmentCanvas.extractBit(out, bit) == 1;
                if (bitOn) {
                    led.getStyleClass().remove("led-off");
                    if (!led.getStyleClass().contains("led-on")) {
                        led.getStyleClass().add("led-on");
                    }
                } else {
                    led.getStyleClass().remove("led-on");
                    if (!led.getStyleClass().contains("led-off")) {
                        led.getStyleClass().add("led-off");
                    }
                }
            }
        }

        if (ramGridView != null) {
            ramGridView.updateFromRamAndPointers(snap.ramCopy(), snap.pc(), snap.mar());
        }
        if (schematicView != null) {
            schematicView.updateFromSnapshot(snap, cpuCore.getMnemonic(snap.ir()));
        }
        highlightSourceLineForPc(snap.pc());
    }

    private void syncTransportControls() {
        boolean running = clockRunner.isRunning();
        boolean halted;
        boolean canUndo;
        synchronized (cpuCore) {
            halted = cpuCore.isHalted();
            canUndo = !cpuCore.getJournal().isEmpty();
        }

        if (codeAsmEditor != null) {
            codeAsmEditor.setEditable(!running);
        }
        if (btnRun != null) {
            btnRun.setDisable(running || halted);
        }
        if (btnPause != null) {
            btnPause.setDisable(!running);
        }
        if (btnStepMicro != null) {
            btnStepMicro.setDisable(running || halted);
        }
        if (btnStepMacro != null) {
            btnStepMacro.setDisable(running || halted);
        }
        if (btnUndoMicro != null) {
            btnUndoMicro.setDisable(running || !canUndo);
        }
        if (btnUndoMacro != null) {
            btnUndoMacro.setDisable(running || !canUndo);
        }
    }

    public void highlightSourceLineForPc(int pc) {
        if (codeAsmEditor == null) {
            return;
        }
        int lineNumber = addressToSourceLine[pc & 0xFF];
        highlightEditorLine(lineNumber);
    }

    private void highlightEditorLine(int oneBasedLine) {
        if (codeAsmEditor == null) {
            return;
        }
        int paragraphCount = codeAsmEditor.getParagraphs().size();
        if (highlightedParagraph >= 0 && highlightedParagraph < paragraphCount) {
            codeAsmEditor.setParagraphStyle(highlightedParagraph, Collections.emptyList());
        }
        int targetIdx = oneBasedLine - 1;
        if (targetIdx >= 0 && targetIdx < paragraphCount) {
            codeAsmEditor.setParagraphStyle(targetIdx, List.of("asm-active-line"));
            highlightedParagraph = targetIdx;
        } else {
            highlightedParagraph = -1;
        }
    }

    private void showDiagnosticsSuccess(String msg) {
        if (lblAsmDiagnostics != null) {
            lblAsmDiagnostics.getStyleClass().remove("diagnostics-error");
            lblAsmDiagnostics.setText(msg);
        }
    }

    private void showDiagnosticsError(String msg) {
        if (lblAsmDiagnostics != null) {
            if (!lblAsmDiagnostics.getStyleClass().contains("diagnostics-error")) {
                lblAsmDiagnostics.getStyleClass().add("diagnostics-error");
            }
            lblAsmDiagnostics.setText(msg);
        }
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spansBuilder.add(Collections.emptyList(), 0);
            return spansBuilder.create();
        }

        Matcher matcher = TOKEN_PATTERN.matcher(text);
        int lastKwEnd = 0;
        while (matcher.find()) {
            String styleClass = null;
            if (matcher.group("COMMENT") != null) {
                styleClass = "asm-comment";
            } else if (matcher.group("LABEL") != null) {
                styleClass = "asm-label";
            } else if (matcher.group("DIRECTIVE") != null) {
                styleClass = "asm-directive";
            } else if (matcher.group("NUMBER") != null) {
                styleClass = "asm-number";
            } else if (matcher.group("WORD") != null) {
                String upperWord = matcher.group("WORD").toUpperCase(Locale.ROOT);
                if (assembler.getInstructionMap().containsKey(upperWord)) {
                    styleClass = "asm-mnemonic";
                }
            }

            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(
                    styleClass == null ? Collections.emptyList() : Collections.singleton(styleClass),
                    matcher.end() - matcher.start()
            );
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    private void reloadIsaDefinitionsIfAvailable() {
        if (isaRepository == null) {
            return;
        }
        try {
            assembler.updateInstructions(isaRepository.listInstructions());
            synchronized (cpuCore) {
                cpuCore.setControlStore(isaRepository.compileControlStore());
            }
        } catch (SQLException ex) {
            showDiagnosticsError("Database error loading ISA: " + ex.getMessage());
        }
    }

    public void setDatabaseManager(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        if (dbManager != null) {
            this.isaRepository = new IsaRepository(dbManager);
            reloadIsaDefinitionsIfAvailable();
            handleAssemble();
        }
    }

    public void shutdown() {
        if (uiTimer != null) {
            uiTimer.stop();
        }
        clockRunner.shutdown();
    }

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }

    public CodeArea getCodeAsmEditor() {
        return codeAsmEditor;
    }

    public CpuCore getCpuCore() {
        return cpuCore;
    }

    public CpuClockRunner getClockRunner() {
        return clockRunner;
    }

    public Assembler getAssembler() {
        return assembler;
    }

    private static String hex2(int value) {
        int v = value & 0xFF;
        char high = Character.toUpperCase(Character.forDigit((v >>> 4) & 0x0F, 16));
        char low = Character.toUpperCase(Character.forDigit(v & 0x0F, 16));
        return "0x" + high + low;
    }
}

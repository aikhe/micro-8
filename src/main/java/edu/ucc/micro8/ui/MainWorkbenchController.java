package edu.ucc.micro8.ui;

import edu.ucc.micro8.asm.AssembledProgram;
import edu.ucc.micro8.asm.Assembler;
import edu.ucc.micro8.asm.SymbolEntry;
import edu.ucc.micro8.asm.SyntaxError;
import edu.ucc.micro8.cpu.CpuCore;
import edu.ucc.micro8.db.DatabaseManager;
import edu.ucc.micro8.db.IsaRepository;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainWorkbenchController {

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
    private Label lblOutReg;

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
    private Button btnClearTrace;

    private CodeArea codeAsmEditor;
    private DatabaseManager dbManager;
    private IsaRepository isaRepository;
    private final CpuCore cpuCore = new CpuCore();
    private final Assembler assembler = new Assembler();
    private int[] addressToSourceLine = new int[256];
    private int highlightedParagraph = -1;

    @FXML
    public void initialize() {
        Arrays.fill(addressToSourceLine, -1);

        if (sldClockHz != null && lblClockHz != null) {
            sldClockHz.valueProperty().addListener((obs, oldVal, newVal) -> {
                int hz = Math.max(1, newVal.intValue());
                lblClockHz.setText(hz + " Hz");
            });
        }

        initSymbolTable();
        initCodeEditor();
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

    @FXML
    public void handleAssemble() {
        if (codeAsmEditor == null) {
            return;
        }
        reloadIsaDefinitionsIfAvailable();

        AssembledProgram program = assembler.assemble(codeAsmEditor.getText());
        if (!program.isSuccess()) {
            SyntaxError first = program.errors().getFirst();
            showDiagnosticsError(first.format() + " (" + program.errors().size() + " error(s))");
            highlightEditorLine(first.lineNumber());
            return;
        }

        this.addressToSourceLine = program.addressToSourceLine();
        cpuCore.wipeCleanSlate();
        cpuCore.loadProgramBytes(0, program.ramImage());

        if (tblSymbolTable != null) {
            tblSymbolTable.setItems(FXCollections.observableArrayList(program.symbolTable()));
        }

        showDiagnosticsSuccess("Ready. Assembled " + program.emittedByteCount() + "B (" + program.symbolTable().size() + " symbol(s)).");
        highlightSourceLineForPc(cpuCore.getPcUnsigned());
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
            cpuCore.setControlStore(isaRepository.compileControlStore());
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

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }

    public CodeArea getCodeAsmEditor() {
        return codeAsmEditor;
    }

    public CpuCore getCpuCore() {
        return cpuCore;
    }

    public Assembler getAssembler() {
        return assembler;
    }
}

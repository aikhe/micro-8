package edu.ucc.micro8.ui;

import edu.ucc.micro8.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;

public final class MainWorkbenchController {

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

    private DatabaseManager dbManager;

    @FXML
    public void initialize() {
        if (sldClockHz != null && lblClockHz != null) {
            sldClockHz.valueProperty().addListener((obs, oldVal, newVal) -> {
                int hz = Math.max(1, newVal.intValue());
                lblClockHz.setText(hz + " Hz");
            });
        }
    }

    public void setDatabaseManager(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }
}

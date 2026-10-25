CREATE TABLE IF NOT EXISTS app_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS control_signals (
    signal_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    bit_position INTEGER NOT NULL UNIQUE,
    is_bus_driver INTEGER NOT NULL CHECK (is_bus_driver IN (0, 1)),
    description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS instructions (
    opcode INTEGER PRIMARY KEY CHECK (opcode >= 0 AND opcode <= 255),
    mnemonic TEXT NOT NULL UNIQUE,
    has_operand INTEGER NOT NULL CHECK (has_operand IN (0, 1)),
    is_builtin INTEGER NOT NULL DEFAULT 1,
    description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS microcode_steps (
    opcode INTEGER NOT NULL,
    t_state INTEGER NOT NULL CHECK (t_state >= 0 AND t_state <= 5),
    flag_state INTEGER NOT NULL CHECK (flag_state >= 0 AND flag_state <= 3),
    control_word INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (opcode, t_state, flag_state),
    FOREIGN KEY (opcode) REFERENCES instructions(opcode) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS microcode_step_signals (
    opcode INTEGER NOT NULL,
    t_state INTEGER NOT NULL,
    flag_state INTEGER NOT NULL,
    signal_id INTEGER NOT NULL,
    PRIMARY KEY (opcode, t_state, flag_state, signal_id),
    FOREIGN KEY (opcode, t_state, flag_state) REFERENCES microcode_steps(opcode, t_state, flag_state) ON DELETE CASCADE,
    FOREIGN KEY (signal_id) REFERENCES control_signals(signal_id)
);

CREATE TABLE IF NOT EXISTS workspace_snapshots (
    snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    assembly_source TEXT NOT NULL,
    ram_blob BLOB NOT NULL CHECK (length(ram_blob) = 256),
    reg_a INTEGER NOT NULL,
    reg_b INTEGER NOT NULL,
    pc INTEGER NOT NULL,
    mar INTEGER NOT NULL,
    ir INTEGER NOT NULL,
    out_reg INTEGER NOT NULL,
    zero_flag INTEGER NOT NULL CHECK (zero_flag IN (0, 1)),
    carry_flag INTEGER NOT NULL CHECK (carry_flag IN (0, 1)),
    t_state INTEGER NOT NULL CHECK (t_state >= 0 AND t_state <= 5),
    cycle_count INTEGER NOT NULL
);

INSERT OR IGNORE INTO app_settings (setting_key, setting_value)
VALUES ('hide_onboarding', '0');

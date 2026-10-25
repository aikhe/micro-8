package dev.frtzhahn.micro8.db;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable persisted workspace snapshot containing editor source, 256B RAM, and CPU registers.
 */
public record WorkspaceSnapshot(
        long snapshotId,
        String name,
        String createdAt,
        String assemblySource,
        byte[] ramBlob,
        int regA,
        int regB,
        int pc,
        int mar,
        int ir,
        int outReg,
        boolean zeroFlag,
        boolean carryFlag,
        int tState,
        int cycleCount
) {

    public static final int RAM_SIZE = 256;

    public WorkspaceSnapshot {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Snapshot name must not be blank");
        }
        name = name.trim();
        createdAt = createdAt == null ? "" : createdAt;
        assemblySource = assemblySource == null ? "" : assemblySource;
        Objects.requireNonNull(ramBlob, "ramBlob must not be null");
        if (ramBlob.length != RAM_SIZE) {
            throw new IllegalArgumentException("ramBlob must be 256 bytes, got " + ramBlob.length);
        }
        ramBlob = Arrays.copyOf(ramBlob, RAM_SIZE);
        regA &= 0xFF;
        regB &= 0xFF;
        pc &= 0xFF;
        mar &= 0xFF;
        ir &= 0xFF;
        outReg &= 0xFF;
        tState = Math.clamp(tState, 0, 5);
    }

    @Override
    public byte[] ramBlob() {
        return Arrays.copyOf(ramBlob, RAM_SIZE);
    }
}

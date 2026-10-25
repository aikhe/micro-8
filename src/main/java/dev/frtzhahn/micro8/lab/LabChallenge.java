package dev.frtzhahn.micro8.lab;

import java.util.List;
import java.util.Objects;

/**
 * Immutable Lab Challenge specification and student progress record.
 */
public record LabChallenge(
        int labId,
        String title,
        String description,
        String starterCode,
        int maxCycles,
        boolean passed,
        int passedCases,
        int totalCases,
        List<LabTestCase> testCases
) {

    public LabChallenge {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(starterCode, "starterCode must not be null");
        testCases = testCases == null ? List.of() : List.copyOf(testCases);
        maxCycles = Math.max(1, maxCycles);
    }

    public String statusText() {
        return (passed ? "PASSED (" : "NOT PASSED (") + passedCases + "/" + totalCases + ")";
    }
}

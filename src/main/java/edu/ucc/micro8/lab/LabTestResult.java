package edu.ucc.micro8.lab;

import java.util.Objects;

/**
 * Immutable grading outcome for a single deterministic LabTestCase.
 */
public record LabTestResult(
        int caseOrder,
        String description,
        boolean passed,
        int cyclesExecuted,
        String expectedSummary,
        String actualSummary,
        String diagnostic
) {

    public LabTestResult {
        Objects.requireNonNull(description, "description must not be null");
        expectedSummary = expectedSummary == null ? "" : expectedSummary;
        actualSummary = actualSummary == null ? "" : actualSummary;
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public String statusLabel() {
        return passed ? "PASS" : "FAIL";
    }
}

package dev.frtzhahn.micro8.lab;

import java.util.List;
import java.util.Objects;

/**
 * Immutable aggregate grading report across all test cases of a LabChallenge.
 */
public record LabGradeReport(
        int labId,
        String labTitle,
        boolean allPassed,
        int passedCases,
        int totalCases,
        List<LabTestResult> caseResults,
        String summaryMessage
) {

    public LabGradeReport {
        Objects.requireNonNull(labTitle, "labTitle must not be null");
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        summaryMessage = summaryMessage == null ? "" : summaryMessage;
    }
}

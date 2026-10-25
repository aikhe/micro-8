package dev.frtzhahn.micro8.lab;

import dev.frtzhahn.micro8.asm.AssembledProgram;
import dev.frtzhahn.micro8.asm.Assembler;
import dev.frtzhahn.micro8.asm.SyntaxError;
import dev.frtzhahn.micro8.cpu.CpuCore;
import dev.frtzhahn.micro8.db.InstructionDef;
import dev.frtzhahn.micro8.db.IsaRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Deterministic headless grader for Clean-Slate Lab Challenges.
 */
public final class LabGrader {

    private final IsaRepository isaRepository;
    private final LabRepository labRepository;

    public LabGrader(IsaRepository isaRepository, LabRepository labRepository) {
        this.isaRepository = Objects.requireNonNull(isaRepository, "isaRepository must not be null");
        this.labRepository = Objects.requireNonNull(labRepository, "labRepository must not be null");
    }

    public static void wipeToCleanSlate(CpuCore cpu) {
        Objects.requireNonNull(cpu, "cpu must not be null");
        synchronized (cpu) {
            cpu.wipeCleanSlate();
        }
    }

    public LabGradeReport grade(LabChallenge lab, String studentSource, CpuCore workbenchCpu) throws SQLException {
        Objects.requireNonNull(lab, "lab must not be null");
        Objects.requireNonNull(workbenchCpu, "workbenchCpu must not be null");

        List<InstructionDef> instructions = isaRepository.listInstructions();
        int[][][] controlStore = isaRepository.compileControlStore();
        Assembler assembler = new Assembler(instructions);

        synchronized (workbenchCpu) {
            workbenchCpu.setControlStore(controlStore);
            for (InstructionDef def : instructions) {
                workbenchCpu.setMnemonic(def.opcode(), def.mnemonic());
            }
            wipeToCleanSlate(workbenchCpu);
        }

        int totalCases = lab.testCases().size();
        AssembledProgram program = assembler.assemble(studentSource == null ? "" : studentSource);
        if (!program.isSuccess()) {
            SyntaxError first = program.errors().getFirst();
            String errMsg = "Assembly failed: " + first.format();
            labRepository.updateLabProgress(lab.labId(), false, 0, totalCases);
            return new LabGradeReport(
                    lab.labId(),
                    lab.title(),
                    false,
                    0,
                    totalCases,
                    List.of(),
                    errMsg
            );
        }

        List<LabTestResult> caseResults = new ArrayList<>(totalCases);
        int passedCount = 0;

        synchronized (workbenchCpu) {
            for (LabTestCase testCase : lab.testCases()) {
                wipeToCleanSlate(workbenchCpu);
                workbenchCpu.loadProgramBytes(0, program.ramImage());

                for (Map.Entry<Integer, Integer> inEntry : testCase.inputRam().entrySet()) {
                    workbenchCpu.writeRam(inEntry.getKey() & 0xFF, inEntry.getValue() & 0xFF);
                }

                int cycles = 0;
                int maxCycles = lab.maxCycles();
                while (!workbenchCpu.isHalted() && cycles < maxCycles) {
                    workbenchCpu.stepMicro();
                    cycles++;
                }

                String expectedSummary = formatExpectedSummary(testCase);
                String actualSummary = formatActualSummary(testCase, workbenchCpu);

                if (!workbenchCpu.isHalted()) {
                    String diag = "Timeout: did not HLT within " + maxCycles + " cycles";
                    caseResults.add(new LabTestResult(
                            testCase.caseOrder(),
                            testCase.description(),
                            false,
                            cycles,
                            expectedSummary,
                            actualSummary,
                            diag
                    ));
                    continue;
                }

                List<String> mismatches = new ArrayList<>();
                if (testCase.expectedOutReg() >= 0) {
                    int actualOut = workbenchCpu.getOutRegUnsigned();
                    int expOut = testCase.expectedOutReg() & 0xFF;
                    if (actualOut != expOut) {
                        mismatches.add("OUT expected " + hexDec(expOut) + " but got " + hexDec(actualOut));
                    }
                }
                if (testCase.expectedRegA() >= 0) {
                    int actualA = workbenchCpu.getRegAUnsigned();
                    int expA = testCase.expectedRegA() & 0xFF;
                    if (actualA != expA) {
                        mismatches.add("RegA expected " + hexDec(expA) + " but got " + hexDec(actualA));
                    }
                }
                for (Map.Entry<Integer, Integer> ramExp : testCase.expectedRam().entrySet()) {
                    int addr = ramExp.getKey() & 0xFF;
                    int expVal = ramExp.getValue() & 0xFF;
                    int actualVal = workbenchCpu.readRamUnsigned(addr);
                    if (actualVal != expVal) {
                        mismatches.add(String.format("RAM[0x%02X] expected %s but got %s", addr, hexDec(expVal), hexDec(actualVal)));
                    }
                }

                boolean casePassed = mismatches.isEmpty();
                if (casePassed) {
                    passedCount++;
                }
                String diag = casePassed ? "PASS (" + cycles + " cycles)" : String.join("; ", mismatches);
                caseResults.add(new LabTestResult(
                        testCase.caseOrder(),
                        testCase.description(),
                        casePassed,
                        cycles,
                        expectedSummary,
                        actualSummary,
                        diag
                ));
            }
        }

        boolean allPassed = (passedCount == totalCases) && totalCases > 0;
        labRepository.updateLabProgress(lab.labId(), allPassed, passedCount, totalCases);

        String summary = (allPassed ? "PASSED " : "FAILED ")
                + passedCount + "/" + totalCases + " test cases for " + lab.title();
        return new LabGradeReport(
                lab.labId(),
                lab.title(),
                allPassed,
                passedCount,
                totalCases,
                caseResults,
                summary
        );
    }

    private static String formatExpectedSummary(LabTestCase tc) {
        StringJoiner joiner = new StringJoiner(", ");
        if (tc.expectedOutReg() >= 0) {
            joiner.add("OUT=" + hexDec(tc.expectedOutReg()));
        }
        if (tc.expectedRegA() >= 0) {
            joiner.add("RegA=" + hexDec(tc.expectedRegA()));
        }
        for (Map.Entry<Integer, Integer> entry : tc.expectedRam().entrySet()) {
            joiner.add(String.format("RAM[0x%02X]=%s", entry.getKey() & 0xFF, hexDec(entry.getValue())));
        }
        return joiner.toString();
    }

    private static String formatActualSummary(LabTestCase tc, CpuCore cpu) {
        StringJoiner joiner = new StringJoiner(", ");
        if (tc.expectedOutReg() >= 0) {
            joiner.add("OUT=" + hexDec(cpu.getOutRegUnsigned()));
        }
        if (tc.expectedRegA() >= 0) {
            joiner.add("RegA=" + hexDec(cpu.getRegAUnsigned()));
        }
        for (Map.Entry<Integer, Integer> entry : tc.expectedRam().entrySet()) {
            int addr = entry.getKey() & 0xFF;
            joiner.add(String.format("RAM[0x%02X]=%s", addr, hexDec(cpu.readRamUnsigned(addr))));
        }
        return joiner.toString();
    }

    private static String hexDec(int val) {
        int u = val & 0xFF;
        return String.format("0x%02X (%d)", u, u);
    }
}

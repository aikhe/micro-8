package dev.frtzhahn.micro8.cpu;

import dev.frtzhahn.micro8.asm.AssembledProgram;
import dev.frtzhahn.micro8.asm.Assembler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuClockRunnerTest {

    @Test
    void clockRunnerExecutesProgramAsynchronouslyAndAutoPausesOnHlt() throws InterruptedException {
        Assembler assembler = new Assembler();
        AssembledProgram prog = assembler.assemble("""
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
                """);
        assertTrue(prog.isSuccess());

        CpuCore cpu = new CpuCore();
        cpu.loadProgramBytes(0, prog.ramImage());

        CpuClockRunner runner = new CpuClockRunner(cpu);
        try {
            runner.setFrequencyHz(1000);
            assertEquals(1000, runner.getFrequencyHz());

            runner.start();
            long deadline = System.currentTimeMillis() + 2500L;
            while (runner.isRunning() && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }

            assertFalse(runner.isRunning());
            synchronized (cpu) {
                assertTrue(cpu.isHalted());
                assertEquals(42, cpu.getOutRegUnsigned());
                assertEquals(17, cpu.getCycleCount());
            }

            CpuSnapshot latest = cpu.consumeLatestSnapshot();
            assertNotNull(latest);
            assertTrue(latest.halted());
            assertEquals(42, latest.outReg());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void pauseFrequencyClampingAndShutdownBehaveDeterministically() {
        CpuCore cpu = new CpuCore();
        CpuClockRunner runner = new CpuClockRunner(cpu);
        try {
            runner.setFrequencyHz(0);
            assertEquals(CpuClockRunner.MIN_HZ, runner.getFrequencyHz());

            runner.setFrequencyHz(5000);
            assertEquals(CpuClockRunner.MAX_HZ, runner.getFrequencyHz());

            runner.start();
            assertTrue(runner.isRunning());

            runner.pause();
            assertFalse(runner.isRunning());
        } finally {
            runner.shutdown();
            assertFalse(runner.isRunning());
        }
    }
}

package dev.frtzhahn.micro8.cpu;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Background daemon clock driver executing CpuCore ticks at 1 Hz to 1000 Hz without touching JavaFX nodes.
 */
public final class CpuClockRunner {

    public static final int MIN_HZ = 1;
    public static final int MAX_HZ = 1000;
    public static final int DEFAULT_HZ = 100;

    private final CpuCore cpuCore;
    private final Object stateLock = new Object();

    private volatile int frequencyHz = DEFAULT_HZ;
    private volatile boolean running;
    private volatile boolean terminated;
    private Thread workerThread;

    public CpuClockRunner(CpuCore cpuCore) {
        this.cpuCore = Objects.requireNonNull(cpuCore, "cpuCore must not be null");
    }

    public void start() {
        synchronized (stateLock) {
            if (terminated) {
                return;
            }
            synchronized (cpuCore) {
                if (cpuCore.isHalted()) {
                    return;
                }
            }
            running = true;
            ensureThreadStarted();
            stateLock.notifyAll();
        }
    }

    public void pause() {
        synchronized (stateLock) {
            running = false;
            stateLock.notifyAll();
        }
    }

    public boolean isRunning() {
        return running && !terminated;
    }

    public void setFrequencyHz(int hz) {
        this.frequencyHz = Math.clamp(hz, MIN_HZ, MAX_HZ);
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    public int getFrequencyHz() {
        return frequencyHz;
    }

    public void shutdown() {
        Thread threadToJoin;
        synchronized (stateLock) {
            terminated = true;
            running = false;
            stateLock.notifyAll();
            threadToJoin = workerThread;
        }
        if (threadToJoin != null) {
            threadToJoin.interrupt();
            try {
                threadToJoin.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void ensureThreadStarted() {
        if (workerThread == null || !workerThread.isAlive()) {
            workerThread = new Thread(this::runClockLoop, "micro8-cpu-clock");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    private void runClockLoop() {
        long nextTickNanos = System.nanoTime();

        while (!terminated) {
            synchronized (stateLock) {
                while (!running && !terminated) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException ignored) {
                        if (terminated) {
                            return;
                        }
                    }
                    nextTickNanos = System.nanoTime();
                }
            }

            if (terminated) {
                return;
            }

            boolean advanced;
            boolean nowHalted;
            synchronized (cpuCore) {
                advanced = cpuCore.stepMicro();
                nowHalted = cpuCore.isHalted();
            }

            if (!advanced || nowHalted) {
                pause();
                continue;
            }

            int hz = Math.clamp(frequencyHz, MIN_HZ, MAX_HZ);
            long periodNanos = 1_000_000_000L / hz;
            nextTickNanos += periodNanos;
            long now = System.nanoTime();
            long sleepNanos = nextTickNanos - now;

            if (sleepNanos > 0) {
                LockSupport.parkNanos(sleepNanos);
            } else if (sleepNanos < -periodNanos * 4) {
                nextTickNanos = now;
            }
        }
    }
}

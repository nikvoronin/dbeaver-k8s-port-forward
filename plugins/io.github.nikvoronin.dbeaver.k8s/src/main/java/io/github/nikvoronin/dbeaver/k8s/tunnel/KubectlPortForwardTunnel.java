/*
 * Kubernetes Port Forward for DBeaver
 * Copyright (C) 2026 dbeaver-k8s-port-forward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nikvoronin.dbeaver.k8s.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single, live {@code kubectl port-forward} process and the state needed to use it as a
 * database tunnel: which local port it bound to, whether it is still alive, and a bounded
 * diagnostic log.
 * <p>
 * Each instance owns exactly one OS process and exactly two reader threads; there is no shared
 * mutable state between instances, so any number of tunnels (e.g. one per DBeaver connection)
 * can run concurrently and independently. {@link #close()} is idempotent and safe to call from
 * any thread, including after a failed {@link #start(KubernetesTunnelConfig)}.
 */
public final class KubectlPortForwardTunnel implements AutoCloseable {

    private static final int DIAGNOSTIC_BUFFER_LINES = 200;
    private static final long READY_POLL_INTERVAL_MILLIS = 25;
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS = 3000;
    private static final long FORCE_SHUTDOWN_TIMEOUT_MILLIS = 3000;
    private static final long READER_JOIN_TIMEOUT_MILLIS = 2000;

    private static final AtomicLong INSTANCE_COUNTER = new AtomicLong();

    private final KubernetesTunnelConfig config;
    private final Process process;
    private final DiagnosticBuffer diagnostics = new DiagnosticBuffer(DIAGNOSTIC_BUFFER_LINES);
    private final AtomicReference<Integer> readyLocalPort = new AtomicReference<>();
    private final Thread stdoutReaderThread;
    private final Thread stderrReaderThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private KubectlPortForwardTunnel(KubernetesTunnelConfig config, Process process) {
        this.config = config;
        this.process = process;

        long id = INSTANCE_COUNTER.incrementAndGet();
        this.stdoutReaderThread = newReaderThread("kubectl-stdout-" + id, process.getInputStream(), true);
        this.stderrReaderThread = newReaderThread("kubectl-stderr-" + id, process.getErrorStream(), false);
        stdoutReaderThread.start();
        stderrReaderThread.start();
    }

    /**
     * Starts {@code kubectl port-forward} for {@code config} and blocks until the tunnel is
     * confirmed ready, kubectl exits early, or the configured startup timeout elapses. Never
     * returns a tunnel that isn't actually ready to accept connections.
     *
     * @throws PortForwardException if kubectl could not be started, exited before becoming
     *      ready, or did not become ready within {@link KubernetesTunnelConfig#getStartupTimeout()}.
     *      In every failure case the underlying process is guaranteed to have been terminated
     *      before this method returns.
     */
    public static KubectlPortForwardTunnel start(KubernetesTunnelConfig config) throws PortForwardException {
        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);
        // Never redirect through a shell; ProcessBuilder passes the argument list straight to
        // CreateProcess/execve, so paths and values containing spaces or special characters are
        // never re-parsed or reinterpreted. kubectl never reads stdin; its input pipe is closed
        // immediately after the process starts (see below) instead of writing anything to it.
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new PortForwardException(
                "kubectl executable not found or could not be started: '" + config.getKubectlExecutable() + "'",
                List.of(), null, e);
        }

        closeQuietly(process.getOutputStream());

        KubectlPortForwardTunnel tunnel = new KubectlPortForwardTunnel(config, process);
        try {
            tunnel.awaitReady(config.getStartupTimeout());
        } catch (PortForwardException e) {
            tunnel.close();
            throw e;
        }
        return tunnel;
    }

    private void awaitReady(Duration timeout) throws PortForwardException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            Integer port = readyLocalPort.get();
            if (port != null) {
                return;
            }
            if (!process.isAlive()) {
                throw new PortForwardException(
                    "kubectl port-forward exited before the tunnel became ready (exit code " + process.exitValue() + ")",
                    diagnostics.snapshot(), process.exitValue());
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new PortForwardException(
                    "Timed out after " + timeout.toSeconds()
                        + " seconds waiting for kubectl port-forward to become ready",
                    diagnostics.snapshot(), null);
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PortForwardException(
                    "Interrupted while waiting for kubectl port-forward to become ready",
                    diagnostics.snapshot(), null, e);
            }
        }
    }

    /** The local TCP port kubectl bound to (never 0 — always the concrete, resolved port). */
    public int getLocalPort() {
        Integer port = readyLocalPort.get();
        if (port == null) {
            throw new IllegalStateException("Tunnel is not ready yet");
        }
        return port;
    }

    public KubernetesTunnelConfig getConfig() {
        return config;
    }

    /** {@code false} once kubectl has exited for any reason, including a clean shutdown. */
    public boolean isRunning() {
        return process.isAlive();
    }

    /** Exit code if kubectl has already exited (including after a successful {@link #close()}); empty while still running. */
    public Optional<Integer> getExitCodeIfExited() {
        if (process.isAlive()) {
            return Optional.empty();
        }
        return Optional.of(process.exitValue());
    }

    /** Recent kubectl stdout/stderr lines, oldest first, bounded in size, for diagnostics. */
    public List<String> getRecentOutput() {
        return diagnostics.snapshot();
    }

    /**
     * Terminates the kubectl process (gracefully, then forcibly if necessary), stops the reader
     * threads and clears runtime state. Safe to call multiple times and safe to call on an
     * instance whose {@link #start(KubernetesTunnelConfig)} call already failed.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (process.isAlive()) {
                terminateProcessTree(false);
                try {
                    if (!process.waitFor(GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        terminateProcessTree(true);
                        process.waitFor(FORCE_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    terminateProcessTree(true);
                }
            }
        } finally {
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            joinQuietly(stdoutReaderThread);
            joinQuietly(stderrReaderThread);
        }
    }

    /**
     * Destroys {@code process} and every descendant of it, not just the immediate child.
     * <p>
     * On Windows, a {@code .cmd}/{@code .bat} "executable" is necessarily run via an implicit
     * {@code cmd.exe /c} wrapper (batch files cannot replace their own process image the way a
     * POSIX shell script can with {@code exec}), so {@code kubectl}-as-a-script scenarios — most
     * notably this plugin's own test-only fake kubectl launcher — leave the real work happening
     * in a grandchild process that a plain {@code process.destroy()} would never reach, leaking
     * it. {@link Process#descendants()} (JDK 9+, cross-platform) finds it regardless of how many
     * layers of wrapping are involved. Real {@code kubectl} has no children of its own, so this
     * is a no-op loop for the common case.
     */
    private void terminateProcessTree(boolean force) {
        process.descendants().forEach(handle -> {
            if (force) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        });
        if (force) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

    private Thread newReaderThread(String name, InputStream stream, boolean isStdout) {
        Thread thread = new Thread(() -> readLines(stream, isStdout), name);
        thread.setDaemon(true);
        return thread;
    }

    private void readLines(InputStream stream, boolean isStdout) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                diagnostics.add((isStdout ? "[stdout] " : "[stderr] ") + line);
                if (isStdout && readyLocalPort.get() == null) {
                    PortForwardOutputParser.parseLocalPort(line, config.getRemotePort())
                        .ifPresent(port -> readyLocalPort.compareAndSet(null, port));
                }
            }
        } catch (IOException e) {
            // Stream closed because the process exited or close() closed it; not an error worth
            // surfacing beyond the diagnostic buffer already collected.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(READER_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

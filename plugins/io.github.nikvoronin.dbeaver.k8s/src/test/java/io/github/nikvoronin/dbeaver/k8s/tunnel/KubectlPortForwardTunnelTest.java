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

import io.github.nikvoronin.dbeaver.k8s.tunnel.testkit.FakeKubectlLauncher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link KubectlPortForwardTunnel} against a fake {@code kubectl} process (see
 * {@link io.github.nikvoronin.dbeaver.k8s.tunnel.testkit}) instead of a real Kubernetes cluster,
 * per TASK.md's requirement that unit tests never require a real cluster.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class KubectlPortForwardTunnelTest {

    private static String fakeKubectl;

    @BeforeAll
    static void setUpFakeKubectl() throws Exception {
        fakeKubectl = FakeKubectlLauncher.createFakeKubectlExecutable();
    }

    private static KubernetesTunnelConfig.Builder scenarioConfig(String scenario) {
        return KubernetesTunnelConfig.builder()
            .kubectlExecutable(fakeKubectl)
            .resource("svc/" + scenario)
            .remotePort(5432)
            .startupTimeout(Duration.ofSeconds(5));
    }

    @Test
    void successfulStartupDiscoversLocalPort() throws Exception {
        KubernetesTunnelConfig config = scenarioConfig("scenario-success").build();

        try (KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config)) {
            assertTrue(tunnel.getLocalPort() > 0);
            assertTrue(tunnel.isRunning());
        }
    }

    @Test
    void delayedSuccessStillCompletesBeforeTimeout() throws Exception {
        KubernetesTunnelConfig config = scenarioConfig("scenario-delayed-success")
            .startupTimeout(Duration.ofSeconds(5))
            .build();

        try (KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config)) {
            assertTrue(tunnel.getLocalPort() > 0);
        }
    }

    @Test
    void explicitLocalPortIsHonoredAndReportedBack() throws Exception {
        KubernetesTunnelConfig config = scenarioConfig("scenario-success")
            .localPort(23456)
            .build();

        try (KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config)) {
            assertEquals(23456, tunnel.getLocalPort());
        }
    }

    @Test
    void immediateFailureRaisesPortForwardExceptionWithDiagnostics() {
        KubernetesTunnelConfig config = scenarioConfig("scenario-immediate-failure").build();

        PortForwardException ex = assertThrowsPortForwardException(config);

        assertTrue(ex.getExitCode() != null && ex.getExitCode() == 1);
        assertTrue(ex.getDiagnosticOutput().stream().anyMatch(line -> line.contains("does not exist")),
            "expected diagnostic output to contain the kubectl error, got: " + ex.getDiagnosticOutput());
    }

    @Test
    void resourceNotFoundIsSurfacedInDiagnostics() {
        KubernetesTunnelConfig config = scenarioConfig("scenario-not-found").build();

        PortForwardException ex = assertThrowsPortForwardException(config);

        assertTrue(ex.getDiagnosticOutput().stream().anyMatch(line -> line.contains("NotFound")));
    }

    @Test
    void portAlreadyInUseIsSurfacedInDiagnostics() {
        KubernetesTunnelConfig config = scenarioConfig("scenario-port-in-use").build();

        PortForwardException ex = assertThrowsPortForwardException(config);

        assertTrue(ex.getDiagnosticOutput().stream().anyMatch(line -> line.contains("unable to listen")));
    }

    @Test
    void timeoutTerminatesTheProcessAndReportsAnActionableMessage() {
        KubernetesTunnelConfig config = scenarioConfig("scenario-timeout")
            .startupTimeout(Duration.ofMillis(300))
            .build();

        long start = System.nanoTime();
        PortForwardException ex = assertThrowsPortForwardException(config);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(ex.getMessage().toLowerCase().contains("timed out"));
        assertTrue(elapsedMillis < 5000, "timeout handling should not itself hang: took " + elapsedMillis + "ms");
    }

    @Test
    void closeIsIdempotent() throws Exception {
        KubernetesTunnelConfig config = scenarioConfig("scenario-success").build();
        KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config);

        tunnel.close();
        assertFalse(tunnel.isRunning());
        // Second close() must not throw.
        tunnel.close();
        assertFalse(tunnel.isRunning());
    }

    @Test
    void closeTerminatesTheUnderlyingProcess() throws Exception {
        KubernetesTunnelConfig config = scenarioConfig("scenario-success").build();
        KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config);

        assertTrue(tunnel.isRunning());
        tunnel.close();
        assertFalse(tunnel.isRunning());
        assertTrue(tunnel.getExitCodeIfExited().isPresent());
    }

    @Test
    void unexpectedExitAfterReadinessIsDetectable() throws Exception {
        KubernetesTunnelConfig config = scenarioConfig("scenario-exit-after-ready").build();

        try (KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config)) {
            assertTrue(tunnel.isRunning(), "should still be running immediately after readiness");

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (tunnel.isRunning() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }

            assertFalse(tunnel.isRunning(), "fake kubectl should have exited on its own by now");
            assertEquals(3, tunnel.getExitCodeIfExited().orElseThrow().intValue());
        }
    }

    @Test
    void multipleConcurrentTunnelsGetIndependentPortsAndLifecycles() throws Exception {
        KubernetesTunnelConfig devConfig = scenarioConfig("scenario-success").namespace("dev").build();
        KubernetesTunnelConfig stageConfig = scenarioConfig("scenario-success").namespace("stage").build();
        KubernetesTunnelConfig prodConfig = scenarioConfig("scenario-success").namespace("prod").build();

        try (KubectlPortForwardTunnel dev = KubectlPortForwardTunnel.start(devConfig);
             KubectlPortForwardTunnel stage = KubectlPortForwardTunnel.start(stageConfig);
             KubectlPortForwardTunnel prod = KubectlPortForwardTunnel.start(prodConfig)) {

            assertTrue(dev.isRunning());
            assertTrue(stage.isRunning());
            assertTrue(prod.isRunning());

            // Disconnecting one must not affect the others (independent lifecycles).
            dev.close();
            assertFalse(dev.isRunning());
            assertTrue(stage.isRunning());
            assertTrue(prod.isRunning());
        }
    }

    @Test
    void diagnosticBufferDoesNotGrowWithoutBound() throws Exception {
        // scenario-success blocks forever without emitting further stdout lines after the
        // initial "Forwarding from" line, so this mainly guards the buffer's own cap logic
        // (see DiagnosticBufferTest) end-to-end through a real process.
        KubernetesTunnelConfig config = scenarioConfig("scenario-success").build();
        try (KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config)) {
            assertTrue(tunnel.getRecentOutput().size() <= 200);
        }
    }

    private static PortForwardException assertThrowsPortForwardException(KubernetesTunnelConfig config) {
        try {
            KubectlPortForwardTunnel tunnel = KubectlPortForwardTunnel.start(config);
            tunnel.close();
            fail("expected PortForwardException, tunnel started successfully instead");
            return null; // unreachable
        } catch (PortForwardException e) {
            return e;
        }
    }
}

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

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesTunnelConfigTest {

    private static KubernetesTunnelConfig.Builder validBuilder() {
        return KubernetesTunnelConfig.builder().resource("svc/postgres").remotePort(5432);
    }

    @Test
    void appliesDocumentedDefaults() {
        KubernetesTunnelConfig config = validBuilder().build();

        assertEquals("kubectl", config.getKubectlExecutable());
        assertEquals("default", config.getNamespace());
        assertEquals("127.0.0.1", config.getBindAddress());
        assertEquals(Duration.ofSeconds(15), config.getStartupTimeout());
        assertTrue(config.isLocalPortAutomatic());
        assertEquals(null, config.getKubeconfigPath());
        assertEquals(null, config.getContext());
    }

    @Test
    void blankResourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().resource("   ").build());
    }

    @Test
    void blankNamespaceFallsBackToDefaultRatherThanBeingRejected() {
        // Empty namespace input is treated as "use the default", matching TASK.md's own
        // documented default ("namespace = default") rather than forcing the user to type it.
        KubernetesTunnelConfig config = validBuilder().namespace("").build();
        assertEquals("default", config.getNamespace());
    }

    @Test
    void remotePortOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().remotePort(0).build());
        assertThrows(IllegalArgumentException.class, () -> validBuilder().remotePort(70000).build());
    }

    @Test
    void explicitLocalPortOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().localPort(-1).build());
        assertThrows(IllegalArgumentException.class, () -> validBuilder().localPort(70000).build());
    }

    @Test
    void automaticLocalPortZeroIsAccepted() {
        KubernetesTunnelConfig config = validBuilder().localPort(0).build();
        assertTrue(config.isLocalPortAutomatic());
    }

    @Test
    void nonPositiveStartupTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().startupTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().startupTimeout(Duration.ofSeconds(-1)).build());
    }

    @Test
    void blankBindAddressFallsBackToLoopbackDefault() {
        KubernetesTunnelConfig config = validBuilder().bindAddress("").build();
        assertEquals("127.0.0.1", config.getBindAddress());
    }

    @Test
    void toStringNeverThrowsAndOmitsNothingButIsHumanReadable() {
        KubernetesTunnelConfig config = validBuilder().context("dev").kubeconfigPath("/tmp/kubeconfig").build();
        String text = config.toString();
        assertTrue(text.contains("svc/postgres"));
        assertTrue(text.contains("dev"));
    }
}

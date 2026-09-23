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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubectlCommandBuilderTest {

    @Test
    void defaultKubectlExecutableIsUsedWhenNotOverridden() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .resource("svc/postgres")
            .remotePort(5432)
            .build();

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        assertEquals("kubectl", command.get(0));
    }

    @Test
    void automaticLocalPortProducesBareColonPrefix() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .resource("svc/postgres")
            .remotePort(5432)
            .build(); // localPort left automatic

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        assertEquals(List.of(
            "kubectl", "--namespace", "default", "port-forward", "--address", "127.0.0.1",
            "svc/postgres", ":5432"
        ), command);
    }

    @Test
    void explicitLocalPortIsRenderedBeforeColon() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .resource("svc/postgres")
            .remotePort(5432)
            .localPort(15432)
            .build();

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        assertTrue(command.contains("15432:5432"));
    }

    @Test
    void kubeconfigPathContainingSpacesIsPassedAsASingleArgument() {
        String pathWithSpaces = "C:\\Users\\Jane Doe\\.kube\\my config.yaml";
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .resource("svc/postgres")
            .remotePort(5432)
            .kubeconfigPath(pathWithSpaces)
            .build();

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        int index = command.indexOf("--kubeconfig");
        assertTrue(index >= 0, "expected --kubeconfig flag to be present");
        // The path must be exactly one list element: never split, never shell-quoted/escaped.
        assertEquals(pathWithSpaces, command.get(index + 1));
    }

    @Test
    void contextAndNamespaceAndResourceAndRemotePortAndBindAddressAppearVerbatim() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .context("dev-cluster")
            .namespace("backend")
            .resource("svc/postgres")
            .remotePort(5432)
            .bindAddress("127.0.0.1")
            .build();

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        assertEquals(List.of(
            "kubectl", "--context", "dev-cluster", "--namespace", "backend",
            "port-forward", "--address", "127.0.0.1", "svc/postgres", ":5432"
        ), command);
    }

    @Test
    void customKubectlExecutablePathIsUsedAsFirstArgument() {
        String customPath = "C:\\Program Files\\Kubernetes\\kubectl.exe";
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .kubectlExecutable(customPath)
            .resource("pod/postgres-0")
            .remotePort(5432)
            .build();

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        assertEquals(customPath, command.get(0));
    }

    @Test
    void noArgumentIsEverAJoinedShellString() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .context("dev-cluster")
            .namespace("backend")
            .resource("svc/postgres")
            .remotePort(5432)
            .kubeconfigPath("/home/user/some kubeconfig.yaml")
            .build();

        List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);

        for (String arg : command) {
            assertFalse(arg.contains("&&") || arg.contains("|") || arg.contains(";"),
                "argument list element must never contain shell metacharacters as a side effect "
                    + "of joining: " + arg);
        }
    }

    @Test
    void resourceKindsOtherThanServiceAreNotRestricted() {
        for (String resource : List.of("svc/postgres", "service/postgres", "pod/postgres-0", "deployment/postgres")) {
            KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
                .resource(resource)
                .remotePort(5432)
                .build();
            List<String> command = KubectlCommandBuilder.buildPortForwardCommand(config);
            assertTrue(command.contains(resource));
        }
    }

    @Test
    void versionCheckCommandUsesConfiguredExecutable() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .kubectlExecutable("/usr/local/bin/kubectl")
            .resource("svc/postgres")
            .remotePort(5432)
            .build();

        List<String> command = KubectlCommandBuilder.buildVersionCheckCommand(config);

        assertEquals(List.of("/usr/local/bin/kubectl", "version", "--client"), command);
    }

    @Test
    void startupTimeoutDefaultsToFifteenSeconds() {
        KubernetesTunnelConfig config = KubernetesTunnelConfig.builder()
            .resource("svc/postgres")
            .remotePort(5432)
            .build();

        assertEquals(Duration.ofSeconds(15), config.getStartupTimeout());
    }
}

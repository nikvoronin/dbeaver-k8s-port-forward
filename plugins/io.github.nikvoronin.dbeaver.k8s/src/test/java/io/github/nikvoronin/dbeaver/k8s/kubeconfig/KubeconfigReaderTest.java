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
package io.github.nikvoronin.dbeaver.k8s.kubeconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeconfigReaderTest {

    private static final String MULTI_CONTEXT_KUBECONFIG = """
        apiVersion: v1
        kind: Config
        current-context: dev
        clusters:
        - name: dev-cluster
          cluster:
            server: https://dev.example.com:6443
        contexts:
        - name: dev
          context:
            cluster: dev-cluster
            namespace: backend
            user: dev-user
        - name: staging
          context:
            cluster: staging-cluster
            user: staging-user
        - context:
            cluster: prod-cluster
            namespace: default
            user: prod-user
          name: prod
        users:
        - name: dev-user
          user:
            token: unused-placeholder-token
        """;

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void parsesContextNamesAndNamespacesFromMultiContextKubeconfig() {
        KubeconfigSummary summary = KubeconfigReader.parse(List.of(MULTI_CONTEXT_KUBECONFIG.split("\n")));

        assertEquals(List.of("dev", "staging", "prod"), summary.contextNames());
        assertEquals("backend", summary.namespaceByContext().get("dev"));
        assertEquals("default", summary.namespaceByContext().get("prod"));
        assertTrue(!summary.namespaceByContext().containsKey("staging"));
        assertEquals(List.of("backend", "default"), summary.distinctNamespaces());
    }

    @Test
    void namesFromOtherTopLevelSectionsDoNotLeakIntoResult() {
        KubeconfigSummary summary = KubeconfigReader.parse(List.of(MULTI_CONTEXT_KUBECONFIG.split("\n")));

        assertTrue(!summary.contextNames().contains("dev-cluster"));
        assertTrue(!summary.contextNames().contains("dev-user"));
    }

    @Test
    void garbageContentYieldsEmptySummaryWithoutThrowing() {
        KubeconfigSummary summary = KubeconfigReader.parse(List.of("not kubeconfig at all", "{{{", "\t\tbinary-ish\u0000junk"));

        assertTrue(summary.isEmpty());
        assertTrue(summary.namespaceByContext().isEmpty());
    }

    @Test
    void blankInputYieldsEmptySummary() {
        assertTrue(KubeconfigReader.parse(List.of()).isEmpty());
    }

    @Test
    void resolvePathPrefersExplicitPathOverEnvAndHome(@TempDir Path tempDir) throws IOException {
        Path explicit = writeFile(tempDir, "explicit.yaml", "contexts: []\n");
        Path envConfig = writeFile(tempDir, "env-config.yaml", "contexts: []\n");

        Path resolved = KubeconfigReader.resolvePath(
            explicit.toString(), Map.of("KUBECONFIG", envConfig.toString()), tempDir.toString());

        assertEquals(explicit, resolved);
    }

    @Test
    void resolvePathFallsBackToKubeconfigEnvVar(@TempDir Path tempDir) throws IOException {
        Path envConfig = writeFile(tempDir, "env-config.yaml", "contexts: []\n");
        Path missing = tempDir.resolve("does-not-exist.yaml");

        String kubeconfigEnv = missing + java.io.File.pathSeparator + envConfig;
        Path resolved = KubeconfigReader.resolvePath(null, Map.of("KUBECONFIG", kubeconfigEnv), tempDir.toString());

        assertEquals(envConfig, resolved);
    }

    @Test
    void resolvePathFallsBackToUserHomeKubeConfig(@TempDir Path tempDir) throws IOException {
        Path kubeDir = Files.createDirectory(tempDir.resolve(".kube"));
        Path defaultConfig = writeFile(kubeDir, "config", "contexts: []\n");

        Path resolved = KubeconfigReader.resolvePath(null, Map.of(), tempDir.toString());

        assertEquals(defaultConfig, resolved);
    }

    @Test
    void resolvePathReturnsNullWhenNothingResolves(@TempDir Path tempDir) {
        Path resolved = KubeconfigReader.resolvePath(null, Map.of(), tempDir.resolve("no-home-here").toString());

        assertNull(resolved);
    }

    @Test
    void readEndToEndParsesARealTempFile(@TempDir Path tempDir) throws IOException {
        Path kubeconfig = writeFile(tempDir, "kubeconfig.yaml", MULTI_CONTEXT_KUBECONFIG);

        KubeconfigSummary summary = KubeconfigReader.read(kubeconfig.toString());

        assertEquals(List.of("dev", "staging", "prod"), summary.contextNames());
        assertEquals("backend", summary.namespaceByContext().get("dev"));
    }

    @Test
    void readReturnsEmptyForMissingFile(@TempDir Path tempDir) {
        KubeconfigSummary summary = KubeconfigReader.read(tempDir.resolve("missing.yaml").toString());

        assertTrue(summary.isEmpty());
    }
}

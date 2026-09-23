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
package io.github.nikvoronin.dbeaver.k8s.tunnel.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Materializes a small, real, executable "fake kubectl" script that launches
 * {@link FakeKubectlMain} in a fresh JVM using the current test run's own classpath. Used instead
 * of a hand-written {@code .bat}/{@code .sh} file checked into the repo so the classpath is
 * always correct for whatever build produced it, on Windows, Linux or macOS alike.
 */
public final class FakeKubectlLauncher {

    private FakeKubectlLauncher() {
    }

    /**
     * @return the absolute path to an executable script that behaves like {@code kubectl} for
     *     the scenarios implemented in {@link FakeKubectlMain}.
     */
    public static String createFakeKubectlExecutable() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaExecutable = Path.of(
            System.getProperty("java.home"), "bin", windows ? "java.exe" : "java"
        ).toString();
        String classpath = System.getProperty("java.class.path");

        Path scriptPath = Files.createTempFile("fake-kubectl-", windows ? ".cmd" : ".sh");
        String mainClass = FakeKubectlMain.class.getName();

        String scriptContent = windows
            ? "@echo off\r\n\"" + javaExecutable + "\" -cp \"" + classpath + "\" " + mainClass + " %*\r\n"
            : "#!/bin/sh\nexec \"" + javaExecutable + "\" -cp \"" + classpath + "\" " + mainClass + " \"$@\"\n";
        Files.writeString(scriptPath, scriptContent);

        if (!windows) {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(scriptPath, perms);
        }

        scriptPath.toFile().deleteOnExit();
        return scriptPath.toAbsolutePath().toString();
    }
}

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

import java.util.Collections;
import java.util.List;

/**
 * Raised whenever a {@code kubectl port-forward} tunnel cannot be started or dies. Always
 * carries the recent kubectl diagnostic output (already redacted of secrets — see
 * {@link KubectlPortForwardTunnel}) so callers can surface an actionable message instead of a
 * bare stack trace.
 */
public final class PortForwardException extends Exception {

    private final List<String> diagnosticOutput;
    private final Integer exitCode;

    public PortForwardException(String message, List<String> diagnosticOutput, Integer exitCode) {
        super(message);
        this.diagnosticOutput = List.copyOf(diagnosticOutput == null ? Collections.emptyList() : diagnosticOutput);
        this.exitCode = exitCode;
    }

    public PortForwardException(String message, List<String> diagnosticOutput, Integer exitCode, Throwable cause) {
        super(message, cause);
        this.diagnosticOutput = List.copyOf(diagnosticOutput == null ? Collections.emptyList() : diagnosticOutput);
        this.exitCode = exitCode;
    }

    /** Recent kubectl stdout/stderr lines at the time of failure, oldest first, bounded in size. */
    public List<String> getDiagnosticOutput() {
        return diagnosticOutput;
    }

    /** The kubectl process exit code, if it had already exited; {@code null} if e.g. a timeout killed it. */
    public Integer getExitCode() {
        return exitCode;
    }

    /**
     * Full actionable message: the headline plus the recent kubectl output, suitable for display
     * to the end user in a DBeaver error dialog.
     */
    public String getDetailedMessage() {
        if (diagnosticOutput.isEmpty()) {
            return getMessage();
        }
        StringBuilder sb = new StringBuilder(getMessage());
        sb.append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("Recent kubectl output:").append(System.lineSeparator());
        for (String line : diagnosticOutput) {
            sb.append("  ").append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }
}

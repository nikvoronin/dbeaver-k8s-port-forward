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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code kubectl port-forward} stdout lines to detect readiness and extract the
 * dynamically allocated local port.
 * <p>
 * Real kubectl output looks like:
 * <pre>
 *   Forwarding from 127.0.0.1:49173 -> 5432
 *   Forwarding from [::1]:49173 -> 5432
 * </pre>
 */
public final class PortForwardOutputParser {

    // Host part is either "[ipv6]" or a bare hostname/IPv4 without ':' or whitespace.
    private static final Pattern FORWARDING_LINE =
        Pattern.compile("^Forwarding from (?:\\[[^\\]]*]|[^:\\s]+):(\\d+) -> (\\d+)$");

    private PortForwardOutputParser() {
    }

    /**
     * @return the local port kubectl bound to, if {@code line} is a "Forwarding from ..." line;
     *     empty for any other line (including malformed/truncated variants).
     */
    public static Optional<Integer> parseLocalPort(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher matcher = FORWARDING_LINE.matcher(line.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Same as {@link #parseLocalPort(String)} but also requires the remote port in the line to
     * match {@code expectedRemotePort}, guarding against a coincidental "Forwarding from" line
     * for a different port (kubectl can forward multiple ports in one process, though this
     * plugin never asks it to).
     */
    public static Optional<Integer> parseLocalPort(String line, int expectedRemotePort) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher matcher = FORWARDING_LINE.matcher(line.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int remotePort = Integer.parseInt(matcher.group(2));
            if (remotePort != expectedRemotePort) {
                return Optional.empty();
            }
            return Optional.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}

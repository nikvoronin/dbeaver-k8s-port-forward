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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe, size-bounded ring buffer of recent kubectl output lines. Prevents unbounded
 * memory growth for long-lived tunnels while retaining enough context to build an actionable
 * failure message.
 */
final class DiagnosticBuffer {

    private final int maxLines;
    private final Deque<String> lines = new ArrayDeque<>();

    DiagnosticBuffer(int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        this.maxLines = maxLines;
    }

    synchronized void add(String line) {
        if (line == null) {
            return;
        }
        if (lines.size() >= maxLines) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }
}

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagnosticBufferTest {

    @Test
    void keepsAllLinesUnderCapacity() {
        DiagnosticBuffer buffer = new DiagnosticBuffer(5);
        buffer.add("a");
        buffer.add("b");

        assertEquals(List.of("a", "b"), buffer.snapshot());
    }

    @Test
    void dropsOldestLinesOnceCapacityIsExceeded() {
        DiagnosticBuffer buffer = new DiagnosticBuffer(3);
        buffer.add("1");
        buffer.add("2");
        buffer.add("3");
        buffer.add("4");
        buffer.add("5");

        assertEquals(List.of("3", "4", "5"), buffer.snapshot());
    }

    @Test
    void nullLinesAreIgnored() {
        DiagnosticBuffer buffer = new DiagnosticBuffer(3);
        buffer.add("a");
        buffer.add(null);
        buffer.add("b");

        assertEquals(List.of("a", "b"), buffer.snapshot());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticBuffer(0));
    }
}

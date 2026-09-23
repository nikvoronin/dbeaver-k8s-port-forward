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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortForwardOutputParserTest {

    @Test
    void parsesIpv4ForwardingLine() {
        Optional<Integer> port = PortForwardOutputParser.parseLocalPort("Forwarding from 127.0.0.1:49173 -> 5432");
        assertEquals(Optional.of(49173), port);
    }

    @Test
    void parsesIpv6ForwardingLine() {
        Optional<Integer> port = PortForwardOutputParser.parseLocalPort("Forwarding from [::1]:49173 -> 5432");
        assertEquals(Optional.of(49173), port);
    }

    @Test
    void parsesHostnameStyleForwardingLine() {
        Optional<Integer> port = PortForwardOutputParser.parseLocalPort("Forwarding from localhost:8443 -> 443");
        assertEquals(Optional.of(8443), port);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Handling connection for 49173",
        "E0921 12:00:00.000000   12345 portforward.go:400] an error occurred forwarding 49173 -> 5432",
        "",
        "Forwarding from 127.0.0.1 -> 5432",              // missing port before '->'
        "Forwarding from 127.0.0.1:49173",                 // truncated, no remote port
        "Forwarding 127.0.0.1:49173 -> 5432",              // missing "from"
        "Forwarding from 127.0.0.1:abc -> 5432",           // non-numeric port
    })
    void irrelevantOrMalformedLinesAreIgnored(String line) {
        assertEquals(Optional.empty(), PortForwardOutputParser.parseLocalPort(line));
    }

    @Test
    void nullLineIsIgnored() {
        assertEquals(Optional.empty(), PortForwardOutputParser.parseLocalPort(null));
    }

    @Test
    void expectedRemotePortOverloadRejectsMismatchedRemotePort() {
        Optional<Integer> port = PortForwardOutputParser.parseLocalPort(
            "Forwarding from 127.0.0.1:49173 -> 5432", 6543);
        assertEquals(Optional.empty(), port);
    }

    @Test
    void expectedRemotePortOverloadAcceptsMatchingRemotePort() {
        Optional<Integer> port = PortForwardOutputParser.parseLocalPort(
            "Forwarding from 127.0.0.1:49173 -> 5432", 5432);
        assertEquals(Optional.of(49173), port);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        Optional<Integer> port = PortForwardOutputParser.parseLocalPort("   Forwarding from 127.0.0.1:49173 -> 5432   ");
        assertTrue(port.isPresent());
    }
}

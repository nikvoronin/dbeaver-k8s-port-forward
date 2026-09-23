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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for the real {@code kubectl} binary, used only by tests. Never touches a real
 * Kubernetes cluster or network. Launched as a plain Java process (see
 * {@link FakeKubectlLauncher}) so the same scenario set behaves identically on Windows, Linux
 * and macOS.
 * <p>
 * The scenario to simulate is read from the "resource" argument produced by
 * {@code KubectlCommandBuilder}, e.g. {@code svc/scenario-success}: this class scans argv for a
 * token containing {@code scenario-} and dispatches on the text after it. The target remote (and,
 * if explicit, local) port is read from the trailing {@code [LOCAL:]REMOTE} argument.
 */
public final class FakeKubectlMain {

    private static final Pattern SCENARIO_TOKEN = Pattern.compile("scenario-(\\S+)");
    private static final Pattern PORT_SPEC = Pattern.compile("^(\\d*):(\\d+)$");

    private FakeKubectlMain() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = findScenario(args);
        PortSpec ports = findPortSpec(args);

        switch (scenario) {
            case "success" -> succeed(ports);
            case "delayed-success" -> {
                Thread.sleep(300);
                succeed(ports);
            }
            case "immediate-failure" -> fail("error: context \"missing\" does not exist");
            case "not-found" -> fail("Error from server (NotFound): services \"postgres\" not found");
            case "port-in-use" -> fail("error: unable to listen on any of the requested ports: [127.0.0.1:5432]");
            case "unauthorized" -> fail("error: Unauthorized");
            case "timeout" -> Thread.sleep(60_000);
            case "exit-after-ready" -> {
                succeedWithoutBlocking(ports);
                Thread.sleep(200);
                System.exit(3);
            }
            default -> {
                System.err.println("fake-kubectl: unknown scenario '" + scenario + "'");
                System.exit(2);
            }
        }
    }

    private static void succeed(PortSpec ports) throws InterruptedException {
        succeedWithoutBlocking(ports);
        // Real kubectl blocks until the pod terminates or it is killed; block "forever" here too
        // so the test harness controls the lifetime by destroying the process.
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void succeedWithoutBlocking(PortSpec ports) {
        int localPort = ports.localPort() != 0 ? ports.localPort() : fakeDynamicPort();
        System.out.println("Forwarding from 127.0.0.1:" + localPort + " -> " + ports.remotePort());
        System.out.flush();
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    /** Derives a pseudo-random but stable-per-process "allocated" port, without binding a real socket. */
    private static int fakeDynamicPort() {
        long pid = ProcessHandle.current().pid();
        return (int) (40000 + (pid % 20000));
    }

    private static String findScenario(String[] args) {
        for (String arg : args) {
            Matcher matcher = SCENARIO_TOKEN.matcher(arg);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "unknown";
    }

    private static PortSpec findPortSpec(String[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            Matcher matcher = PORT_SPEC.matcher(args[i]);
            if (matcher.matches()) {
                String localPart = matcher.group(1);
                int local = localPart.isEmpty() ? 0 : Integer.parseInt(localPart);
                int remote = Integer.parseInt(matcher.group(2));
                return new PortSpec(local, remote);
            }
        }
        return new PortSpec(0, 0);
    }

    private record PortSpec(int localPort, int remotePort) {
    }
}

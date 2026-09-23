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

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@code kubectl port-forward} argument lists as {@code List<String>}, suitable for
 * {@link ProcessBuilder}. Never builds a shell command string and never invokes a shell.
 * <p>
 * Verified argument syntax against {@code kubectl port-forward --help} (client v1.36):
 * <pre>
 *   kubectl port-forward TYPE/NAME [options] [LOCAL_PORT:]REMOTE_PORT
 * </pre>
 * A bare {@code :REMOTE_PORT} (no local port before the colon) asks kubectl to pick a free
 * local port itself and print it in the "Forwarding from ..." line — this is the "automatic
 * local port" mechanism this plugin relies on (see {@link PortForwardOutputParser}), avoiding
 * the select-a-free-port-then-race-to-bind-it problem.
 */
public final class KubectlCommandBuilder {

    private KubectlCommandBuilder() {
    }

    /**
     * Builds the full {@code kubectl ... port-forward ...} argument list for the given config.
     */
    public static List<String> buildPortForwardCommand(KubernetesTunnelConfig config) {
        List<String> args = new ArrayList<>();
        args.add(config.getKubectlExecutable());
        addGlobalFlags(args, config);
        args.add("port-forward");
        args.add("--address");
        args.add(config.getBindAddress());
        args.add(config.getResource());
        args.add(localPortSpec(config) + ":" + config.getRemotePort());
        return args;
    }

    /**
     * Builds a lightweight {@code kubectl version --client} command, used by the UI's
     * "Test kubectl" action to validate the configured executable path without touching
     * any cluster.
     */
    public static List<String> buildVersionCheckCommand(KubernetesTunnelConfig config) {
        List<String> args = new ArrayList<>();
        args.add(config.getKubectlExecutable());
        args.add("version");
        args.add("--client");
        return args;
    }

    private static void addGlobalFlags(List<String> args, KubernetesTunnelConfig config) {
        if (config.getKubeconfigPath() != null) {
            args.add("--kubeconfig");
            args.add(config.getKubeconfigPath());
        }
        if (config.getContext() != null) {
            args.add("--context");
            args.add(config.getContext());
        }
        args.add("--namespace");
        args.add(config.getNamespace());
    }

    private static String localPortSpec(KubernetesTunnelConfig config) {
        return config.isLocalPortAutomatic() ? "" : String.valueOf(config.getLocalPort());
    }
}

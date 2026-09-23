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
package io.github.nikvoronin.dbeaver.k8s.handler;

/**
 * Network handler extension id and {@link org.jkiss.dbeaver.model.net.DBWHandlerConfiguration}
 * property keys, shared between {@code plugin.xml}, {@link KubernetesTunnelHandler} and the UI
 * configurator module.
 */
public final class KubernetesTunnelConstants {

    /** Must match the {@code id} attribute of the {@code <handler>} element in plugin.xml. */
    public static final String HANDLER_ID = "k8s_port_forward";

    public static final String PROP_KUBECTL_PATH = "kubectlPath";
    public static final String PROP_KUBECONFIG = "kubeconfig";
    public static final String PROP_CONTEXT = "context";
    public static final String PROP_NAMESPACE = "namespace";
    public static final String PROP_RESOURCE = "resource";
    public static final String PROP_REMOTE_PORT = "remotePort";
    /** Stored as a string; blank or "0" both mean "automatic". */
    public static final String PROP_LOCAL_PORT = "localPort";
    public static final String PROP_BIND_ADDRESS = "bindAddress";
    /** Stored in seconds. */
    public static final String PROP_STARTUP_TIMEOUT = "startupTimeout";

    public static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 15;

    private KubernetesTunnelConstants() {
    }
}

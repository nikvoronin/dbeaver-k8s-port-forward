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

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable, validated description of a single {@code kubectl port-forward} tunnel.
 * <p>
 * Has no dependency on DBeaver or Eclipse APIs so it can be constructed and unit
 * tested independently of a running DBeaver instance.
 */
public final class KubernetesTunnelConfig {

    public static final String DEFAULT_KUBECTL_EXECUTABLE = "kubectl";
    public static final String DEFAULT_NAMESPACE = "default";
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int AUTOMATIC_LOCAL_PORT = 0;
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(15);

    private final String kubectlExecutable;
    private final String kubeconfigPath;
    private final String context;
    private final String namespace;
    private final String resource;
    private final int remotePort;
    private final int localPort;
    private final String bindAddress;
    private final Duration startupTimeout;

    private KubernetesTunnelConfig(Builder builder) {
        this.kubectlExecutable = builder.kubectlExecutable;
        this.kubeconfigPath = builder.kubeconfigPath;
        this.context = builder.context;
        this.namespace = builder.namespace;
        this.resource = builder.resource;
        this.remotePort = builder.remotePort;
        this.localPort = builder.localPort;
        this.bindAddress = builder.bindAddress;
        this.startupTimeout = builder.startupTimeout;
    }

    public String getKubectlExecutable() {
        return kubectlExecutable;
    }

    /** May be {@code null}/empty: means "let kubectl resolve its own default kubeconfig". */
    public String getKubeconfigPath() {
        return kubeconfigPath;
    }

    /** May be {@code null}/empty: means "use the kubeconfig's current-context". */
    public String getContext() {
        return context;
    }

    public String getNamespace() {
        return namespace;
    }

    /** kubectl resource reference, e.g. {@code svc/postgres}, {@code pod/postgres-0}. */
    public String getResource() {
        return resource;
    }

    public int getRemotePort() {
        return remotePort;
    }

    /** {@link #AUTOMATIC_LOCAL_PORT} (0) means "let kubectl pick a free local port". */
    public int getLocalPort() {
        return localPort;
    }

    public boolean isLocalPortAutomatic() {
        return localPort == AUTOMATIC_LOCAL_PORT;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        // Deliberately omits kubeconfigPath contents beyond the path itself: the path is not
        // a secret, but nothing here ever includes kubeconfig file *contents*, tokens, etc.
        return "KubernetesTunnelConfig{" +
            "kubectlExecutable='" + kubectlExecutable + '\'' +
            ", kubeconfigPath='" + kubeconfigPath + '\'' +
            ", context='" + context + '\'' +
            ", namespace='" + namespace + '\'' +
            ", resource='" + resource + '\'' +
            ", remotePort=" + remotePort +
            ", localPort=" + (isLocalPortAutomatic() ? "automatic" : localPort) +
            ", bindAddress='" + bindAddress + '\'' +
            ", startupTimeout=" + startupTimeout +
            '}';
    }

    public static final class Builder {
        private String kubectlExecutable = DEFAULT_KUBECTL_EXECUTABLE;
        private String kubeconfigPath;
        private String context;
        private String namespace = DEFAULT_NAMESPACE;
        private String resource;
        private int remotePort;
        private int localPort = AUTOMATIC_LOCAL_PORT;
        private String bindAddress = DEFAULT_BIND_ADDRESS;
        private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;

        private Builder() {
        }

        public Builder kubectlExecutable(String kubectlExecutable) {
            this.kubectlExecutable = blankToDefault(kubectlExecutable, DEFAULT_KUBECTL_EXECUTABLE);
            return this;
        }

        public Builder kubeconfigPath(String kubeconfigPath) {
            this.kubeconfigPath = nullIfBlank(kubeconfigPath);
            return this;
        }

        public Builder context(String context) {
            this.context = nullIfBlank(context);
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = blankToDefault(namespace, DEFAULT_NAMESPACE);
            return this;
        }

        public Builder resource(String resource) {
            this.resource = resource == null ? null : resource.trim();
            return this;
        }

        public Builder remotePort(int remotePort) {
            this.remotePort = remotePort;
            return this;
        }

        public Builder localPort(int localPort) {
            this.localPort = localPort;
            return this;
        }

        public Builder bindAddress(String bindAddress) {
            this.bindAddress = blankToDefault(bindAddress, DEFAULT_BIND_ADDRESS);
            return this;
        }

        public Builder startupTimeout(Duration startupTimeout) {
            this.startupTimeout = startupTimeout;
            return this;
        }

        public KubernetesTunnelConfig build() {
            Objects.requireNonNull(resource, "resource must not be null");
            if (resource.isBlank()) {
                throw new IllegalArgumentException("Kubernetes resource must not be blank (e.g. 'svc/postgres')");
            }
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("Kubernetes namespace must not be blank");
            }
            if (remotePort < 1 || remotePort > 65535) {
                throw new IllegalArgumentException("Remote port must be in range 1..65535, got " + remotePort);
            }
            if (localPort != AUTOMATIC_LOCAL_PORT && (localPort < 1 || localPort > 65535)) {
                throw new IllegalArgumentException(
                    "Local port must be 'automatic' (0) or in range 1..65535, got " + localPort);
            }
            if (bindAddress == null || bindAddress.isBlank()) {
                throw new IllegalArgumentException("Bind address must not be blank");
            }
            if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
                throw new IllegalArgumentException("Startup timeout must be positive, got " + startupTimeout);
            }
            if (kubectlExecutable == null || kubectlExecutable.isBlank()) {
                throw new IllegalArgumentException("kubectl executable must not be blank");
            }
            return new KubernetesTunnelConfig(this);
        }

        private static String blankToDefault(String value, String defaultValue) {
            return (value == null || value.isBlank()) ? defaultValue : value.trim();
        }

        private static String nullIfBlank(String value) {
            return (value == null || value.isBlank()) ? null : value.trim();
        }
    }
}

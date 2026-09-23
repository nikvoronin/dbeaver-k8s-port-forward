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

import io.github.nikvoronin.dbeaver.k8s.tunnel.KubectlPortForwardTunnel;
import io.github.nikvoronin.dbeaver.k8s.tunnel.KubernetesTunnelConfig;
import io.github.nikvoronin.dbeaver.k8s.tunnel.PortForwardException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCInvalidatePhase;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.DBWTunnel;
import org.jkiss.dbeaver.model.net.DBWUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DBeaver {@code type="tunnel"} network handler ("Kubernetes") that forwards the JDBC
 * connection through {@code kubectl port-forward} instead of requiring the database to be
 * directly reachable from the machine running DBeaver.
 * <p>
 * Registered as the {@code handlerClass} of the {@code k8s_port_forward} extension in
 * {@code plugin.xml}. See {@code docs/dbeaver-api-notes.md} for the full trace of how DBeaver
 * drives this interface ({@link org.jkiss.dbeaver.registry.DataSourceDescriptor#connect}).
 * <p>
 * One instance is created per connect attempt (DBeaver calls
 * {@code DBWHandlerConfiguration.createHandler()} fresh every time, it does not reuse
 * instances), and each instance owns exactly one {@link KubectlPortForwardTunnel} — so this
 * class holds no static/shared mutable state and multiple simultaneous connections never
 * interfere with each other.
 */
public class KubernetesTunnelHandler implements DBWTunnel {

    private static final Log log = Log.getLog(KubernetesTunnelHandler.class);

    private DBWHandlerConfiguration configuration;
    private volatile KubectlPortForwardTunnel activeTunnel;
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    @NotNull
    @Override
    public DBPConnectionConfiguration initializeHandler(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBWHandlerConfiguration configuration,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException {
        this.configuration = configuration;

        KubernetesTunnelConfig tunnelConfig = buildTunnelConfig(configuration, connectionInfo);

        log.debug("Starting Kubernetes port-forward tunnel (context=" + safe(tunnelConfig.getContext())
            + ", namespace=" + tunnelConfig.getNamespace()
            + ", resource=" + tunnelConfig.getResource()
            + ", remotePort=" + tunnelConfig.getRemotePort() + ")");

        monitor.subTask("Starting kubectl port-forward");
        try {
            activeTunnel = KubectlPortForwardTunnel.start(tunnelConfig);
        } catch (PortForwardException e) {
            activeTunnel = null;
            log.error("Kubernetes tunnel failed to start", e);
            throw new DBException(e.getDetailedMessage(), e);
        }

        log.debug("Kubernetes tunnel ready on local port " + activeTunnel.getLocalPort());

        DBPConnectionConfiguration updated = new DBPConnectionConfiguration(connectionInfo);
        DBWUtils.updateConfigWithTunnelInfo(configuration, updated, tunnelConfig.getBindAddress(), activeTunnel.getLocalPort());
        return updated;
    }

    @NotNull
    private KubernetesTunnelConfig buildTunnelConfig(
        @NotNull DBWHandlerConfiguration configuration,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException {
        KubernetesTunnelConfig.Builder builder = KubernetesTunnelConfig.builder()
            .kubectlExecutable(configuration.getStringProperty(KubernetesTunnelConstants.PROP_KUBECTL_PATH))
            .kubeconfigPath(configuration.getStringProperty(KubernetesTunnelConstants.PROP_KUBECONFIG))
            .context(configuration.getStringProperty(KubernetesTunnelConstants.PROP_CONTEXT))
            .namespace(configuration.getStringProperty(KubernetesTunnelConstants.PROP_NAMESPACE))
            .resource(configuration.getStringProperty(KubernetesTunnelConstants.PROP_RESOURCE))
            .bindAddress(configuration.getStringProperty(KubernetesTunnelConstants.PROP_BIND_ADDRESS))
            .localPort(CommonUtils.toInt(configuration.getStringProperty(KubernetesTunnelConstants.PROP_LOCAL_PORT), 0));

        int remotePort = configuration.getIntProperty(KubernetesTunnelConstants.PROP_REMOTE_PORT, 0);
        if (remotePort <= 0) {
            // Mirrors the built-in SSH handler: an unset remote port falls back to the
            // connection's own configured database port, since in the common case (a Service
            // whose port matches the DB's own "Port" field) the user shouldn't have to repeat it.
            remotePort = CommonUtils.toInt(connectionInfo.getHostPort(), 0);
        }
        builder.remotePort(remotePort);

        int timeoutSeconds = configuration.getIntProperty(
            KubernetesTunnelConstants.PROP_STARTUP_TIMEOUT, KubernetesTunnelConstants.DEFAULT_STARTUP_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = KubernetesTunnelConstants.DEFAULT_STARTUP_TIMEOUT_SECONDS;
        }
        builder.startupTimeout(Duration.ofSeconds(timeoutSeconds));

        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new DBException("Invalid Kubernetes tunnel configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean matchesParameters(@NotNull String host, int port) {
        // Unlike SSH/SOCKS, this handler's "next hop" isn't a reusable host:port pair — it's a
        // (context, namespace, resource, port) tuple resolved by kubectl/the API server. There is
        // no host:port DBeaver could ask us about that would meaningfully "match" this tunnel.
        // See docs/dbeaver-api-notes.md section 8.
        return false;
    }

    @NotNull
    @Override
    public AuthCredentials getRequiredCredentials(@NotNull DBWHandlerConfiguration configuration) {
        // Kubernetes authentication is delegated entirely to kubectl/kubeconfig (exec plugins,
        // cloud CLIs, OIDC, etc. all keep working); this handler never needs DBeaver to prompt
        // for or store a password of its own.
        return AuthCredentials.NONE;
    }

    @Override
    public void invalidateHandler(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCInvalidatePhase phase
    ) throws DBException {
        KubectlPortForwardTunnel tunnel = activeTunnel;
        if (tunnel == null) {
            return;
        }
        if (!tunnel.isRunning()) {
            String exitInfo = tunnel.getExitCodeIfExited()
                .map(code -> " (exit code " + code + ")")
                .orElse("");
            String message = "Kubernetes port-forward process exited unexpectedly" + exitInfo
                + " — the connection needs to be reconnected.";
            log.warn(message);
            throw new DBException(message + formatDiagnostics(tunnel.getRecentOutput()));
        }
        // Tunnel process is still alive: nothing to do. v1 deliberately implements no automatic
        // reconnection (see TASK.md "no complex auto-recovery in v1" / docs/architecture.md).
    }

    @Override
    public void closeTunnel(@NotNull DBRProgressMonitor monitor) {
        KubectlPortForwardTunnel tunnel = activeTunnel;
        activeTunnel = null;
        if (tunnel != null) {
            log.debug("Closing Kubernetes port-forward tunnel");
            tunnel.close();
        }
        for (Runnable listener : closeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.debug("Kubernetes tunnel close listener failed", e);
            }
        }
        closeListeners.clear();
    }

    @Nullable
    @Override
    public Object getImplementation() {
        return activeTunnel;
    }

    @Override
    public void addCloseListener(@NotNull Runnable listener) {
        closeListeners.add(listener);
    }

    private static String formatDiagnostics(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(System.lineSeparator()).append(System.lineSeparator())
            .append("Recent kubectl output:").append(System.lineSeparator());
        for (String line : lines) {
            sb.append("  ").append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value == null ? "<default>" : value;
    }
}

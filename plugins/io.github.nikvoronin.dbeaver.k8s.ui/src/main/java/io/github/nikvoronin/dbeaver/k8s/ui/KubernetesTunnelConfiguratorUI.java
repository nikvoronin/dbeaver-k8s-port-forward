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
package io.github.nikvoronin.dbeaver.k8s.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import io.github.nikvoronin.dbeaver.k8s.handler.KubernetesTunnelConstants;
import io.github.nikvoronin.dbeaver.k8s.tunnel.KubectlCommandBuilder;
import io.github.nikvoronin.dbeaver.k8s.tunnel.KubernetesTunnelConfig;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.ui.IObjectPropertyConfigurator;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SWT settings panel for the "Kubernetes" network handler, registered against
 * {@link io.github.nikvoronin.dbeaver.k8s.handler.KubernetesTunnelHandler} via the
 * {@code org.jkiss.dbeaver.ui.propertyConfigurator} extension point (see
 * {@code io.github.nikvoronin.dbeaver.k8s.ui/plugin.xml} and {@code docs/dbeaver-api-notes.md}
 * section 5).
 * <p>
 * Deliberately does not draw its own "Enable" checkbox: DBeaver's connection wizard already
 * puts one on the tab itself (see {@code ConnectionPageSettings}/{@code ConnectionPageNetworkHandler}
 * in DBeaver's own source) — every built-in handler (SSH, SOCKS) follows the same convention.
 */
public class KubernetesTunnelConfiguratorUI implements IObjectPropertyConfigurator<Object, DBWHandlerConfiguration> {

    private Text kubectlPathText;
    private Text kubeconfigText;
    private Text contextText;
    private Text namespaceText;
    private Text resourceText;
    private Spinner remotePortSpinner;
    private Button automaticLocalPortCheckbox;
    private Spinner localPortSpinner;
    private Text bindAddressText;
    private Spinner startupTimeoutSpinner;
    private Label testResultLabel;

    private Runnable propertyChangeListener;

    @Override
    public void createControl(@NotNull Composite parent, Object object, @NotNull Runnable propertyChangeListener) {
        this.propertyChangeListener = propertyChangeListener;

        Composite composite = UIUtils.createComposite(parent, 2);
        composite.setLayoutData(new GridData(GridData.FILL_BOTH));

        kubectlPathText = createBrowsableField(composite, "Kubectl executable", "Select kubectl executable", false);
        kubeconfigText = createBrowsableField(composite, "Kubeconfig", "Select kubeconfig file", false);

        contextText = UIUtils.createLabelText(composite, "Context", "");
        contextText.setToolTipText("kubeconfig context to use (blank = kubeconfig's current-context)");

        namespaceText = UIUtils.createLabelText(composite, "Namespace", KubernetesTunnelConfig.DEFAULT_NAMESPACE);

        resourceText = UIUtils.createLabelText(composite, "Resource", "");
        resourceText.setToolTipText("kubectl resource reference, e.g. svc/postgres, pod/postgres-0, deployment/postgres");

        remotePortSpinner = UIUtils.createLabelSpinner(composite, "Remote port", 0, 1, 65535);
        remotePortSpinner.setToolTipText("Port exposed by the resource inside the cluster (blank/0 = use the connection's own port)");

        createLocalPortRow(composite);

        bindAddressText = UIUtils.createLabelText(composite, "Bind address", KubernetesTunnelConfig.DEFAULT_BIND_ADDRESS);
        bindAddressText.setToolTipText(
            "Local address to bind. Keep 127.0.0.1 unless you specifically need other hosts to reach the "
                + "forwarded port — a non-loopback address exposes the database to your network.");

        startupTimeoutSpinner = UIUtils.createLabelSpinner(
            composite, "Startup timeout (seconds)", (int) KubernetesTunnelConfig.DEFAULT_STARTUP_TIMEOUT.toSeconds(), 1, 600);

        createTestKubectlRow(composite);

        registerChangeListeners();
    }

    private Text createBrowsableField(Composite parent, String label, String dialogTitle, boolean directory) {
        UIUtils.createControlLabel(parent, label);

        Composite row = UIUtils.createComposite(parent, 2);
        row.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Text text = new Text(row, SWT.BORDER);
        text.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        UIUtils.createPushButton(row, "Browse...", null, SelectionListener.widgetSelectedAdapter(e -> {
            FileDialog dialog = new FileDialog(row.getShell(), directory ? SWT.OPEN : SWT.OPEN | SWT.SINGLE);
            dialog.setText(dialogTitle);
            String selected = dialog.open();
            if (selected != null) {
                text.setText(selected);
            }
        }));

        return text;
    }

    private void createLocalPortRow(Composite parent) {
        UIUtils.createControlLabel(parent, "Local port");

        Composite row = UIUtils.createComposite(parent, 2);
        row.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        localPortSpinner = new Spinner(row, SWT.BORDER);
        localPortSpinner.setMinimum(1);
        localPortSpinner.setMaximum(65535);
        localPortSpinner.setSelection(1);
        localPortSpinner.setEnabled(false);
        localPortSpinner.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING));

        automaticLocalPortCheckbox = UIUtils.createCheckbox(row, "Automatic", true);

        automaticLocalPortCheckbox.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            localPortSpinner.setEnabled(!automaticLocalPortCheckbox.getSelection());
            propertyChangeListener.run();
        }));
    }

    private void createTestKubectlRow(Composite parent) {
        UIUtils.createControlLabel(parent, "Test client");

        Composite row = UIUtils.createComposite(parent, 2);
        row.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        UIUtils.createPushButton(row, "Test kubectl", null,
            SelectionListener.widgetSelectedAdapter(e -> testKubectl()));

        testResultLabel = new Label(row, SWT.NONE);
        testResultLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
    }

    private void testKubectl() {
        String kubectlPath = orDefault(kubectlPathText.getText().trim(), KubernetesTunnelConfig.DEFAULT_KUBECTL_EXECUTABLE);
        testResultLabel.setText("Testing...");

        Thread testThread = new Thread(() -> {
            String resultText;
            try {
                KubernetesTunnelConfig probeConfig = KubernetesTunnelConfig.builder()
                    .kubectlExecutable(kubectlPath)
                    // Resource/remote port are irrelevant to a version check but required by the
                    // builder's validation; any placeholder value satisfies it.
                    .resource("svc/probe")
                    .remotePort(1)
                    .build();
                List<String> command = KubectlCommandBuilder.buildVersionCheckCommand(probeConfig);

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();
                String output;
                try {
                    output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                } finally {
                    process.getInputStream().close();
                }
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    process.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    resultText = "Timed out running kubectl";
                } else if (process.exitValue() == 0) {
                    resultText = "OK: " + firstLine(output);
                } else {
                    resultText = "kubectl exited with code " + process.exitValue() + ": " + firstLine(output);
                }
            } catch (IOException e) {
                resultText = "kubectl not found or could not be started: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resultText = "Interrupted";
            } catch (IllegalArgumentException e) {
                resultText = "Invalid kubectl path";
            }
            String finalResultText = resultText;
            UIUtils.asyncExec(() -> {
                if (!testResultLabel.isDisposed()) {
                    testResultLabel.setText(finalResultText);
                }
            });
        }, "kubernetes-tunnel-test-kubectl");
        testThread.setDaemon(true);
        testThread.start();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }

    private void registerChangeListeners() {
        ModifyListener modifyListener = e -> propertyChangeListener.run();
        kubectlPathText.addModifyListener(modifyListener);
        kubeconfigText.addModifyListener(modifyListener);
        contextText.addModifyListener(modifyListener);
        namespaceText.addModifyListener(modifyListener);
        resourceText.addModifyListener(modifyListener);
        remotePortSpinner.addModifyListener(modifyListener);
        localPortSpinner.addModifyListener(modifyListener);
        bindAddressText.addModifyListener(modifyListener);
        startupTimeoutSpinner.addModifyListener(modifyListener);
    }

    @Override
    public void loadSettings(@NotNull DBWHandlerConfiguration configuration) {
        kubectlPathText.setText(orDefault(
            configuration.getStringProperty(KubernetesTunnelConstants.PROP_KUBECTL_PATH), KubernetesTunnelConfig.DEFAULT_KUBECTL_EXECUTABLE));
        kubeconfigText.setText(CommonUtils.notEmpty(configuration.getStringProperty(KubernetesTunnelConstants.PROP_KUBECONFIG)));
        contextText.setText(CommonUtils.notEmpty(configuration.getStringProperty(KubernetesTunnelConstants.PROP_CONTEXT)));
        namespaceText.setText(orDefault(
            configuration.getStringProperty(KubernetesTunnelConstants.PROP_NAMESPACE), KubernetesTunnelConfig.DEFAULT_NAMESPACE));
        resourceText.setText(CommonUtils.notEmpty(configuration.getStringProperty(KubernetesTunnelConstants.PROP_RESOURCE)));

        remotePortSpinner.setSelection(Math.max(0, configuration.getIntProperty(KubernetesTunnelConstants.PROP_REMOTE_PORT, 0)));

        int localPort = CommonUtils.toInt(configuration.getStringProperty(KubernetesTunnelConstants.PROP_LOCAL_PORT), 0);
        boolean automatic = localPort <= 0;
        automaticLocalPortCheckbox.setSelection(automatic);
        localPortSpinner.setEnabled(!automatic);
        localPortSpinner.setSelection(automatic ? 1 : localPort);

        bindAddressText.setText(orDefault(
            configuration.getStringProperty(KubernetesTunnelConstants.PROP_BIND_ADDRESS), KubernetesTunnelConfig.DEFAULT_BIND_ADDRESS));

        int timeout = configuration.getIntProperty(
            KubernetesTunnelConstants.PROP_STARTUP_TIMEOUT, KubernetesTunnelConstants.DEFAULT_STARTUP_TIMEOUT_SECONDS);
        startupTimeoutSpinner.setSelection(timeout > 0 ? timeout : KubernetesTunnelConstants.DEFAULT_STARTUP_TIMEOUT_SECONDS);

        if (testResultLabel != null) {
            testResultLabel.setText("");
        }
    }

    @Override
    public void saveSettings(@NotNull DBWHandlerConfiguration configuration) {
        configuration.setProperty(KubernetesTunnelConstants.PROP_KUBECTL_PATH, trimToNull(kubectlPathText.getText()));
        configuration.setProperty(KubernetesTunnelConstants.PROP_KUBECONFIG, trimToNull(kubeconfigText.getText()));
        configuration.setProperty(KubernetesTunnelConstants.PROP_CONTEXT, trimToNull(contextText.getText()));
        configuration.setProperty(KubernetesTunnelConstants.PROP_NAMESPACE, trimToNull(namespaceText.getText()));
        configuration.setProperty(KubernetesTunnelConstants.PROP_RESOURCE, trimToNull(resourceText.getText()));
        configuration.setProperty(KubernetesTunnelConstants.PROP_REMOTE_PORT, remotePortSpinner.getSelection());
        configuration.setProperty(
            KubernetesTunnelConstants.PROP_LOCAL_PORT,
            automaticLocalPortCheckbox.getSelection() ? 0 : localPortSpinner.getSelection());
        configuration.setProperty(KubernetesTunnelConstants.PROP_BIND_ADDRESS, trimToNull(bindAddressText.getText()));
        configuration.setProperty(KubernetesTunnelConstants.PROP_STARTUP_TIMEOUT, startupTimeoutSpinner.getSelection());
    }

    @Override
    public void resetSettings(@NotNull DBWHandlerConfiguration configuration) {
        // No-op, matching the built-in SOCKS proxy configurator's convention: the wizard reloads
        // via loadSettings() when the user re-opens/re-selects the tab.
    }

    @Override
    public boolean isComplete() {
        return CommonUtils.isNotEmpty(resourceText.getText());
    }

    @Override
    public String getErrorMessage() {
        if (CommonUtils.isEmpty(resourceText.getText())) {
            return "Kubernetes resource must not be blank (e.g. svc/postgres)";
        }
        return null;
    }

    private static String orDefault(String value, String defaultValue) {
        return CommonUtils.isEmpty(value) ? defaultValue : value;
    }

    private static String trimToNull(String value) {
        String trimmed = value == null ? null : value.trim();
        return CommonUtils.isEmpty(trimmed) ? null : trimmed;
    }
}

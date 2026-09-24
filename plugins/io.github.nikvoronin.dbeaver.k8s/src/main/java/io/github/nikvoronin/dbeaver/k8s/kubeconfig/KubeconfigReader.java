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
package io.github.nikvoronin.dbeaver.k8s.kubeconfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves and reads whatever kubeconfig is relevant to the UI's "Kubeconfig" field, purely to
 * seed suggestion combos for Context/Namespace. Deliberately not a general YAML parser: it only
 * understands the top-level {@code contexts:} block of a standard, kubectl-style
 * block-formatted kubeconfig (as produced by kubectl itself, kind, minikube, and cloud CLIs).
 * Anything it doesn't recognise (a missing file, flow-style YAML, anchors, ...) simply yields
 * {@link KubeconfigSummary#EMPTY} rather than an error — this must never surface an exception to
 * the UI, since it runs synchronously on the SWT thread whenever the field changes.
 * <p>
 * Has no dependency on DBeaver, Eclipse or SWT, so it can be unit tested independently, matching
 * the {@code tunnel} package's convention (see {@code docs/architecture.md}).
 */
public final class KubeconfigReader {

    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^[\\w.-]+:.*$");
    private static final Pattern NAME_LINE = Pattern.compile("^name:\\s*(.+?)\\s*$");
    private static final Pattern NAMESPACE_LINE = Pattern.compile("^namespace:\\s*(.+?)\\s*$");

    private KubeconfigReader() {
    }

    public static KubeconfigSummary read(String explicitPath) {
        try {
            Path resolved = resolvePath(explicitPath, System.getenv(), System.getProperty("user.home"));
            if (resolved == null || !Files.isReadable(resolved)) {
                return KubeconfigSummary.EMPTY;
            }
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            return parse(lines);
        } catch (Exception e) {
            return KubeconfigSummary.EMPTY;
        }
    }

    /**
     * Picks the first readable candidate among {@code explicitPath}, the {@code KUBECONFIG} env
     * var's entries, and {@code <userHome>/.kube/config} — in that order. Unlike real kubectl,
     * this does not merge multiple {@code KUBECONFIG} entries into one logical config: it only
     * exists to seed suggestion combos, not to drive an actual connection.
     */
    static Path resolvePath(String explicitPath, Map<String, String> env, String userHome) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return Path.of(explicitPath.trim());
        }
        String kubeconfigEnv = env.get("KUBECONFIG");
        if (kubeconfigEnv != null && !kubeconfigEnv.isBlank()) {
            for (String candidate : kubeconfigEnv.split(Pattern.quote(File.pathSeparator))) {
                if (candidate.isBlank()) {
                    continue;
                }
                Path candidatePath = Path.of(candidate.trim());
                if (Files.isReadable(candidatePath)) {
                    return candidatePath;
                }
            }
        }
        if (userHome != null && !userHome.isBlank()) {
            Path defaultPath = Path.of(userHome, ".kube", "config");
            if (Files.isReadable(defaultPath)) {
                return defaultPath;
            }
        }
        return null;
    }

    static KubeconfigSummary parse(List<String> lines) {
        List<String> contextNames = new ArrayList<>();
        Map<String, String> namespaceByContext = new LinkedHashMap<>();

        boolean inContextsBlock = false;
        int contextsBlockIndent = 0;
        Integer entryIndent = null;
        String currentName = null;
        String currentNamespace = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = indentOf(line);

            if (!inContextsBlock) {
                if (indent == 0 && trimmed.equals("contexts:")) {
                    inContextsBlock = true;
                    contextsBlockIndent = indent;
                    entryIndent = null;
                    currentName = null;
                    currentNamespace = null;
                }
                continue;
            }

            boolean isEntryMarker = trimmed.startsWith("-");
            boolean isBlockEnd = !isEntryMarker && indent <= contextsBlockIndent && TOP_LEVEL_KEY.matcher(trimmed).matches();
            if (isBlockEnd) {
                commitEntry(contextNames, namespaceByContext, currentName, currentNamespace);
                inContextsBlock = false;
                currentName = null;
                currentNamespace = null;
                continue;
            }

            if (isEntryMarker) {
                if (entryIndent == null) {
                    entryIndent = indent;
                }
                if (indent == entryIndent) {
                    commitEntry(contextNames, namespaceByContext, currentName, currentNamespace);
                    currentName = null;
                    currentNamespace = null;
                }
            }

            String content = isEntryMarker ? stripDash(trimmed) : trimmed;

            if (currentName == null) {
                Matcher nameMatcher = NAME_LINE.matcher(content);
                if (nameMatcher.matches()) {
                    currentName = unquote(nameMatcher.group(1));
                    continue;
                }
            }
            if (currentNamespace == null) {
                Matcher namespaceMatcher = NAMESPACE_LINE.matcher(content);
                if (namespaceMatcher.matches()) {
                    currentNamespace = unquote(namespaceMatcher.group(1));
                }
            }
        }

        commitEntry(contextNames, namespaceByContext, currentName, currentNamespace);

        return new KubeconfigSummary(List.copyOf(contextNames), Map.copyOf(namespaceByContext));
    }

    private static void commitEntry(List<String> names, Map<String, String> namespaces, String name, String namespace) {
        if (name == null) {
            return;
        }
        names.add(name);
        if (namespace != null) {
            namespaces.put(name, namespace);
        }
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String stripDash(String trimmed) {
        return trimmed.substring(1).stripLeading();
    }

    private static String unquote(String value) {
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}

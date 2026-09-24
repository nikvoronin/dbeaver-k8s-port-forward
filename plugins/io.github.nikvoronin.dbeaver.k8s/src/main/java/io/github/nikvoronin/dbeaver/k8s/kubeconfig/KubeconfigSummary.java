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

import java.util.List;
import java.util.Map;

/**
 * Context names and per-context default namespaces found in a kubeconfig file, for populating
 * UI suggestion combos. Never represents a parse failure as an exception — an unreadable or
 * unrecognized kubeconfig simply produces {@link #EMPTY}.
 */
public record KubeconfigSummary(List<String> contextNames, Map<String, String> namespaceByContext) {

    public static final KubeconfigSummary EMPTY = new KubeconfigSummary(List.of(), Map.of());

    public boolean isEmpty() {
        return contextNames.isEmpty();
    }

    /** Distinct, sorted namespace values across all contexts that declared one. */
    public List<String> distinctNamespaces() {
        return namespaceByContext.values().stream().distinct().sorted().toList();
    }
}

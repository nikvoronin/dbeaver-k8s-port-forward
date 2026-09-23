# Troubleshooting

Every row below is: symptom you see in DBeaver → likely cause → command to run yourself to
confirm it → fix. The plugin always includes the raw, recent `kubectl` output in the DBeaver
error dialog (bounded to the last 200 lines) — read that first, it usually names the exact cause.

## Installation problems (before you ever get to a "Kubernetes" tab)

These two are about getting the plugin itself loaded, not about Kubernetes/kubectl — see
README.md "Installation" for the full install procedure.

| Symptom | Likely cause | Diagnostic | Remediation |
| --- | --- | --- | --- |
| "Kubernetes" never appears as a network handler at all, even after restarting DBeaver (with or without `-clean`) | Jars were dropped into `dropins/`, but DBeaver's p2 dropins reconciler isn't wired to auto-scan it (seen on DBeaver 26.2.1 `.exe` builds — see `docs/dbeaver-api-notes.md` section 7) | Check `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` in the install dir for `io.github.nikvoronin.dbeaver.k8s` — if absent, dropins never registered it | Run `scripts\Install-DBeaverPlugin.ps1 -DBeaverHome <path>` instead of using dropins |
| DBeaver's "Help → Install New Software..." wizard shows "There are no items available" pointed at `repository/target/repository` | You're on an older checkout from before the `repository/` module existed (it published plain bundles with no Feature/Category, which the wizard refuses to list) — see `docs/dbeaver-api-notes.md` sections 7 and 9 | Check whether `repository/target/repository/content.jar` exists and whether `repository/pom.xml` exists in your checkout | Update/rebuild (`.\mvnw.cmd clean verify`); if you deliberately want the old bundle-only p2 tooling instead, use `scripts\Install-DBeaverPlugin.ps1 -DBeaverHome <path>` |
| The "Kubernetes" tab appears, but its settings panel is completely empty (no fields, no "Test kubectl" button) | The UI bundle's `org.jkiss.dbeaver.ui.propertyConfigurator` extension failed to register — usually because its `plugin.xml` failed to parse | Check `<DBeaverData>/workspace*/.metadata/.log` for an `!ENTRY org.eclipse.equinox.registry ... Could not parse XML contribution for "io.github.nikvoronin.dbeaver.k8s.ui//plugin.xml"` entry (a `SAXParseException` will name the exact line/column) | Fix the reported XML issue in `plugins/io.github.nikvoronin.dbeaver.k8s.ui/src/main/resources/plugin.xml` (e.g. a literal `--` inside an XML comment is illegal XML and will break parsing), then rerun `scripts\Install-DBeaverPlugin.ps1 -DBeaverHome <path>` to rebuild and reinstall |
| Need to remove the plugin entirely | — | — | `scripts\Install-DBeaverPlugin.ps1 -DBeaverHome <path> -Uninstall` |

### Diagnosing extension-registry / OSGi resolution problems

If the plugin's jars are clearly present (per the row above) but "Kubernetes" still doesn't show
up, or the UI panel is empty, check these before anything else:

- **Help → About → Installation Details → Plug-ins** — confirms whether
  `io.github.nikvoronin.dbeaver.k8s` and `io.github.nikvoronin.dbeaver.k8s.ui` are actually listed
  as installed bundles at all, and their reported state (e.g. `ACTIVE` vs `RESOLVED`/`INSTALLED`
  without ever starting).
- **DBeaver's own Error Log** (the same view referenced in the row above,
  `<DBeaverData>/workspace*/.metadata/.log`, also browsable from **Help → About → Installation
  Details → Configuration** or an in-app Error Log view depending on version) — OSGi resolution
  failures and `plugin.xml` parse errors are logged here with a full stack trace.
- Running DBeaver **once** with `-clean -consoleLog` (i.e. `dbeaver.exe -clean -consoleLog` /
  `dbeaverc.exe -clean -consoleLog`) forces the OSGi framework to rebuild its bundle cache from
  scratch and echoes framework/registry diagnostics straight to the console instead of only the
  log file — useful when the log file itself looks empty or stale. **`-clean` is a one-off
  diagnostic step, not something to add to normal startup** — it meaningfully slows down the next
  launch and should be dropped once you're done investigating.

## Kubernetes/kubectl problems (once the tab itself works)

| Symptom in DBeaver | Likely cause | Diagnostic command | Remediation |
| --- | --- | --- | --- |
| `kubectl executable not found or could not be started: 'kubectl'` | The configured "kubectl executable" path is wrong, or `kubectl` isn't on `PATH` for the account running DBeaver | `kubectl version --client` (in the same shell/account context DBeaver runs under) | Set an absolute path in the "kubectl executable" field, or fix `PATH` |
| `error: context "X" does not exist` (in the recent output) | The configured "Context" doesn't exist in the resolved kubeconfig | `kubectl config get-contexts` | Fix the "Context" field, or leave it blank to use the kubeconfig's `current-context` |
| `Unauthorized` / `error: You must be logged in to the server (Unauthorized)` | kubeconfig credentials are invalid or expired (token, exec-plugin, OIDC, cloud CLI session) | `kubectl auth whoami` or `kubectl get ns` using the same `--kubeconfig`/`--context` | Refresh credentials the same way you would for any other `kubectl` use (`aws eks get-token`, `gcloud container clusters get-credentials`, re-login, etc.) — this plugin never manages credentials itself |
| `Error from server (Forbidden): ... is forbidden: User "..." cannot ...` | Authenticated, but RBAC doesn't grant `pods/portforward` (and usually `get`/`list` on the resource) in the namespace | `kubectl auth can-i create pods/portforward -n <namespace>` | Ask a cluster admin to grant `pods/portforward` (and `get` on the resource kind) in that namespace |
| `Error from server (NotFound): services "X" not found` (or `pods`/`deployments`) | Wrong "Resource" value or wrong "Namespace" | `kubectl -n <namespace> get svc` / `get pods` / `get deployments` | Fix "Resource"/"Namespace"; remember the resource must include its kind prefix, e.g. `svc/postgres` |
| `Error from server (NotFound): pods "X" not found` even though the Service exists | The Service has no matching/ready Endpoints (label selector mismatch, or the backing Pod isn't Running yet) | `kubectl -n <namespace> get endpoints <service>` and `kubectl -n <namespace> get pods` | Fix the Service's selector, or wait for the Pod to become Ready |
| `error: unable to listen on any of the requested ports: [...]` | The requested **local** port is already in use (only relevant if "Local port" is set to an explicit value, not Automatic) | `netstat -ano \| findstr <port>` (Windows) | Prefer "Local port: Automatic"; if you need a fixed port, free it or pick another |
| `Timed out after N seconds waiting for kubectl port-forward to become ready` | Slow API server / cluster, or kubectl started but never printed a "Forwarding from" line for some other reason (see the attached recent output) | `kubectl -n <namespace> port-forward <resource> :<remotePort>` run by hand, timed | Raise "Startup timeout"; if the manual command also hangs, the problem is the cluster/network, not this plugin |
| `kubectl port-forward exited before the tunnel became ready (exit code N)` | kubectl itself failed fast — check the attached recent output for the real reason (usually one of the rows above) | — | Fix per the underlying kubectl error shown |
| `Kubernetes port-forward process exited unexpectedly ... needs to be reconnected` (during use, not at connect) | The pod behind the Service was replaced/rescheduled, or the API server dropped the connection, or the machine went to sleep | `kubectl -n <namespace> get pods` (check for a recent restart) | Reconnect the DBeaver connection — the plugin does not auto-reconnect in v1 (see `docs/architecture.md`) |
| DBeaver connects, but then fails with a **database** error (not a kubectl one) | The tunnel worked; the problem is downstream, in PostgreSQL itself | See below | See below |
| `FATAL: password authentication failed for user "X"` | Tunnel is fine; PostgreSQL credentials are wrong | `psql -h 127.0.0.1 -p <local-port> -U <user> <db>` using the same local port DBeaver connected to | Fix the DBeaver connection's username/password — unrelated to Kubernetes |
| SSL/TLS handshake failure from the JDBC driver | Tunnel is fine; the JDBC driver's SSL mode doesn't match what the server (or a TLS-terminating proxy in front of it) expects | Same `psql -h 127.0.0.1 -p <local-port> ...` test, varying `sslmode` | Adjust the DBeaver connection's SSL settings — unrelated to Kubernetes |
| Connection refused to `127.0.0.1:<port>` right after DBeaver reports the tunnel ready | Rare race between kubectl's own readiness message and its listener actually accepting; or a local firewall blocking loopback (unusual) | Re-run "Test Connection"; `netstat -ano \| findstr <port>` to confirm something is listening | If reproducible, file an issue with the attached kubectl output — this plugin trusts kubectl's own "Forwarding from" message as the readiness signal (see `docs/architecture.md`) |

## General diagnostic commands

```bash
kubectl config current-context
kubectl config get-contexts
kubectl get namespace
kubectl -n <namespace> get svc
kubectl -n <namespace> get pods
kubectl -n <namespace> port-forward svc/postgres :5432
```

Running the last command by hand, with the same context/namespace/resource/port you configured
in DBeaver, reproduces exactly what this plugin does internally — if it works by hand but not
from DBeaver, compare the exact values field-by-field (a stray leading/trailing space in
"Resource" or "Context" is the most common cause).

## Distinguishing failure categories at a glance

```
Kubernetes authentication failure   -> Unauthorized                         -> fix kubeconfig credentials
Kubernetes authorization/RBAC       -> Forbidden                            -> fix RBAC (pods/portforward)
resource lookup failure             -> NotFound (services/pods/deployments) -> fix Resource/Namespace
port-forward failure (local)        -> unable to listen on any of the ...   -> free the port or use Automatic
PostgreSQL authentication failure   -> FATAL: password authentication ...   -> fix DB credentials (not this plugin)
JDBC SSL failure                    -> SSL/TLS handshake error from driver  -> fix DBeaver SSL settings (not this plugin)
```

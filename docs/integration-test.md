# Manual integration test (real Kubernetes + PostgreSQL)

This is a manual procedure — it needs a real Kubernetes cluster and a real DBeaver
installation with this plugin's jars dropped into it. It is **not** part of the automated test
suite (`mvnw clean verify`): unit tests never require a real cluster. This document only
prescribes the procedure.

All credentials below are placeholders for a disposable, local test cluster (e.g.
[`kind`](https://kind.sigs.k8s.io/) or [`minikube`](https://minikube.sigs.k8s.io/)). Do not point
this procedure at a production cluster, and do not reuse the placeholder password anywhere real.

## 1. Create a namespace

```bash
kubectl create namespace dbeaver-test
```

## 2. Create a disposable PostgreSQL workload

```yaml
# dbeaver-test-postgres.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  namespace: dbeaver-test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          env:
            - name: POSTGRES_PASSWORD
              value: "test-only-password-do-not-reuse"
            - name: POSTGRES_DB
              value: postgres
          ports:
            - containerPort: 5432
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "postgres"]
            initialDelaySeconds: 5
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: dbeaver-test
spec:
  selector:
    app: postgres
  ports:
    - port: 5432
      targetPort: 5432
```

```bash
kubectl apply -f dbeaver-test-postgres.yaml
kubectl -n dbeaver-test rollout status deployment/postgres
```

## 3. Verify direct kubectl port-forward works

```bash
kubectl -n dbeaver-test port-forward svc/postgres :5432
```

Confirm it prints a line like `Forwarding from 127.0.0.1:XXXXX -> 5432`, then, in a second
terminal:

```bash
psql -h 127.0.0.1 -p XXXXX -U postgres -c '\conninfo'
# password: test-only-password-do-not-reuse
```

Stop the manual `port-forward` (Ctrl+C) once this works — the plugin will manage its own process.

## 4. Configure a DBeaver connection

Install the plugin first (see README.md "Installation"), then create a new PostgreSQL
connection with:

```text
Database:              postgres
Username:              postgres
Password:              test-only-password-do-not-reuse
Original database host: postgres
Original database port: 5432
```

("Original database host/port" can be any placeholder — the Kubernetes handler replaces both
before JDBC ever sees them — but using the Service name as a reminder of the real target is
conventional, matching how the SSH tunnel handler's own examples are written.)

## 5. Enable the Kubernetes handler

In the connection's "Kubernetes" tab:

```text
context:         kind-dbeaver-test        (or whatever `kubectl config current-context` prints)
namespace:        dbeaver-test
resource:         svc/postgres
remote port:      5432
local port:       Automatic
```

Tick the tab's own "Use" checkbox to enable the handler (DBeaver draws this on the tab itself,
not inside the panel — see `docs/dbeaver-api-notes.md` section 5).

## 6. Click "Test Connection"

Expected: DBeaver reports success within a few seconds. If it fails, the error dialog includes
the plugin's own message plus the recent `kubectl` output — cross-reference with
`docs/troubleshooting.md`.

## 7. Verify database connectivity

Open a SQL editor on the new connection and run:

```sql
select version();
```

## 8. Inspect the running kubectl process

While the DBeaver connection is open:

```powershell
Get-CimInstance Win32_Process -Filter "Name='kubectl.exe'" |
  Select-Object ProcessId, CommandLine
```

(or `ps aux | grep "port-forward"` on Linux/macOS). Confirm exactly one `kubectl ... port-forward
... svc/postgres ...` process exists, with `--namespace dbeaver-test` and `--context
<your-context>` in its command line.

## 9. Disconnect

Disconnect the DBeaver connection (or close DBeaver).

## 10. Verify process termination

Re-run the same process-listing command from step 8 — the `kubectl port-forward` process must be
gone. No orphan process should remain.

## 11. (Optional) Repeat with multiple simultaneous connections

Duplicate the connection twice more (e.g. pointing at different namespaces, or the same
namespace with different connection names), enable the Kubernetes handler on all three, connect
all three at once, and confirm in step 8's process listing that there are three independent
`kubectl` processes on three different local ports, and that disconnecting one leaves the other
two running.

## 12. Remove the test Kubernetes resources

```bash
kubectl delete namespace dbeaver-test
```

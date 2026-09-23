# DBeaver Community Edition — Network Handler API notes

This document is a reference for the DBeaver extension points, classes and lifecycle this
plugin relies on. It records the exact classes, extension points and lifecycle used by the
built-in SSH tunnel and SOCKS proxy handlers, traced end to end, and the decisions this plugin
bases on them.

Traced against a local checkout of the `dbeaver/dbeaver` Community Edition source, under
`.reference\dbeaver` (git remote `https://github.com/dbeaver/dbeaver.git`, branch `devel`,
commit `fc2a972a8d79c017ff505d42135ce6b39f8719b3`, product version `26.2.2.qualifier`,
`Bundle-RequiredExecutionEnvironment: JavaSE-21`).

## 1. Extension point: network handler registration

Declared in `plugins/org.jkiss.dbeaver.registry/plugin.xml`:

```xml
<extension-point id="org.jkiss.dbeaver.networkHandler"
                  name="%extension-point.org.jkiss.dbeaver.networkHandler.name"
                  schema="schema/org.jkiss.dbeaver.networkHandler.exsd"/>
```

Schema file: `plugins/org.jkiss.dbeaver.registry/schema/org.jkiss.dbeaver.networkHandler.exsd`.
The `.exsd` is stale relative to actual usage: real registrations also use `codeName`,
`prefix`, `desktop`, `order`, `distributed`, none of which appear in the `.exsd`. The
authoritative reference is `NetworkHandlerDescriptor`, not the schema file.

Real registration example — SSH tunnel, `plugins/org.jkiss.dbeaver.net.ssh/plugin.xml`:

```xml
<extension point="org.jkiss.dbeaver.networkHandler">
    <handler
            type="tunnel"
            id="ssh_tunnel"
            codeName="SSH"
            prefix="ssh"
            label="%handler.ssh_tunnel.label"
            description="%handler.ssh_tunnel.description"
            desktop="false"
            secured="true"
            order="1"
            handlerClass="org.jkiss.dbeaver.model.net.ssh.SSHTunnelImpl">
        <propertyGroup label="SSH Settings">
            <property id="host" label="Host" type="string" .../>
            ...
        </propertyGroup>
    </handler>
</extension>
```

Simpler example — SOCKS proxy, `plugins/org.jkiss.dbeaver.registry/plugin.xml`:

```xml
<extension point="org.jkiss.dbeaver.networkHandler">
    <handler type="proxy" id="socks_proxy" codeName="Proxy" prefix="proxy"
             label="%handler.socks_proxy.label" desktop="true" secured="true" order="3"
             handlerClass="org.jkiss.dbeaver.model.impl.net.SocksProxyImpl"/>
</extension>
```

`type` maps directly to the enum `org.jkiss.dbeaver.model.net.DBWHandlerType`:

```java
public enum DBWHandlerType { TUNNEL, PROXY, CONFIG }
```

`TUNNEL` is the type that gets its `initializeHandler()` result used to *replace* the
JDBC host/port (see §4). This plugin registers with `type="tunnel"`.

Registration is parsed by `org.jkiss.dbeaver.registry.network.NetworkHandlerDescriptor`
(implements `org.jkiss.dbeaver.model.net.DBWHandlerDescriptor`). Handler visibility per
driver: `NetworkHandlerRegistry.getDescriptors(driver)` includes every handler that has
no declared `<objectType>` driver restriction (ours has none, so it is offered for every
JDBC driver — this plugin is a generic tunnel, not tied to a specific driver) — **unless**
`DBAPermissionRealm.FEATURE_SSH_TUNNELING` is unsupported by the workspace, in which
case *all* `TUNNEL`-type handlers (not just SSH) are hidden. The default (desktop,
single-user) `BaseWorkspaceImpl.supportsRealmFeature()` always returns `true`, so this
only matters for DBeaver's multi-user/team server products, not Community Edition.

## 2. Handler implementation interfaces

`plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/net/`:

- **`DBWNetworkHandler`** — base contract for every handler:
  - `DBPConnectionConfiguration initializeHandler(DBRProgressMonitor, DBWHandlerConfiguration, DBPConnectionConfiguration) throws DBException, IOException`
  - `void invalidateHandler(DBRProgressMonitor, DBPDataSource, DBCInvalidatePhase) throws DBException`
  - `default DBPDataSourceContainer[] getDependentDataSources()`
- **`DBWForwarder`** — `boolean matchesParameters(String host, int port)` — used by
  `DBExecUtils` to recognise when a raw host/port pair actually belongs to a live
  forwarder (SSH's own jump-host address). Not required for correctness of a tunnel
  whose remote endpoint isn't a single reusable host/port pair.
- **`DBWTunnel extends DBWNetworkHandler, DBWForwarder`** — the interface a `type="tunnel"`
  handler's `handlerClass` must implement:
  - `AuthCredentials getRequiredCredentials(DBWHandlerConfiguration) throws DBException` — enum `NONE|CREDENTIALS|PASSWORD`, drives whether DBeaver prompts for a password before connecting.
  - `void closeTunnel(DBRProgressMonitor) throws DBException, IOException`
  - `Object getImplementation()`
  - `void addCloseListener(Runnable)`

No Eclipse/SWT types appear anywhere in this contract — `DBRProgressMonitor` is
DBeaver's own abstraction (`org.jkiss.dbeaver.model.runtime`), not
`org.eclipse.core.runtime.IProgressMonitor`. This is why the core tunnel-engine module
of this plugin only needs to compile against `org.jkiss.dbeaver.model` (which itself
re-exports `org.jkiss.utils` and `org.eclipse.core.runtime`), not against SWT/JFace.

## 3. Configuration object

`org.jkiss.dbeaver.model.net.DBWHandlerConfiguration` — one instance per configured
handler per connection (or per network profile). Not a singleton, not shared between
unrelated connections. Relevant surface used by this plugin:

- `boolean isEnabled()` / `setEnabled(boolean)`
- `String getStringProperty(String)`, `int getIntProperty(String)`,
  `int getIntProperty(String, int default)`, `boolean getBooleanProperty(String, boolean)`,
  `void setProperty(String, Object)` — free-form key/value bag, persisted as part of the
  connection's stored configuration (non-secure properties in the connection JSON,
  `@SecureProperty`-annotated ones such as `userName`/`password`/`secureProperties` go
  through DBeaver's secret storage instead of plain JSON).
- `DBWHandlerDescriptor getHandlerDescriptor()`, `DBPDriver getDriver()`,
  `DBPDataSourceContainer getDataSource()`.

Property values authored in `plugin.xml` `<propertyGroup>/<property>` blocks
(`type="string|boolean|integer|file|..."`, see
`org.jkiss.dbeaver.model.impl.PropertyDescriptor.PropertyType`) are metadata only —
**the actual configuration UI does not auto-render them.** A separate, hand-written SWT
panel (see §5) reads/writes `DBWHandlerConfiguration` properties by string key. The
`<propertyGroup>` declarations are kept for documentation/introspection (e.g. the CLI's
`ListNetworkHandlersParameterHandler`) and this plugin declares them for the same reason,
mirroring the SSH plugin.

## 4. Connect / disconnect lifecycle (traced end-to-end)

Driven entirely by `org.jkiss.dbeaver.registry.DataSourceDescriptor` (the concrete
`DBPDataSourceContainer` implementation), **not** by anything handler-specific — every
tunnel handler is invoked identically through this code path:

```text
DataSourceDescriptor.connect(monitor, initialize, reflect)
  -> resolvedConnectionInfo.getHandlers() -> pick the single enabled TUNNEL config
       (and independently, the single enabled PROXY config)
  -> initTunnelHandler(monitor, tunnelConfiguration, connectionInfo):
       tunnelHandler = tunnelConfiguration.createHandler(DBWTunnel.class)   // reflection, new instance per connect
       if (!savePassword) tunnelHandler.getRequiredCredentials(...)        // maybe prompt
       return tunnelHandler.initializeHandler(monitor, tunnelConfiguration, connectionInfo)
  -> resolvedConnectionInfo = <value returned above>   // host/port/url now point at the tunnel
  -> openDataSource(monitor, initialize)               // JDBC connect happens here, using the *replaced* endpoint
```

`initializeHandler()` is expected to build a **copy** of `connectionInfo`
(`new DBPConnectionConfiguration(connectionInfo)`), start whatever is needed, and call:

```java
DBWUtils.updateConfigWithTunnelInfo(configuration, connectionInfo, localHost, localPort);
```

which sets `connectionInfo.setHostName(localHost)` (defaults to `127.0.0.1` if
`localHost` is blank and the original host wasn't already `localhost`/`local`),
`connectionInfo.setHostPort(String.valueOf(localPort))`, and regenerates the JDBC URL
from the driver's URL template. **This is the exact, supported mechanism for endpoint
substitution** — the handler never has to make DBeaver "believe" the DB host is
localhost; it returns a `DBPConnectionConfiguration` that already says so.

Error propagation: if `initializeHandler()` throws, `initTunnelHandler()` wraps it in a
`DBCException`; `DataSourceDescriptor.connect()`'s outer `catch (Throwable e)` then calls
`closeTunnelHandler(monitor)` and rethrows as `DBException` — **the JDBC connect step
(`openDataSource`) is never reached**, so a failed tunnel can never silently fall back to
the original (untunnelled) endpoint.

Disconnect:

```java
protected void closeTunnelHandler(DBRProgressMonitor monitor) {
    if (tunnelHandler != null) {
        try { tunnelHandler.closeTunnel(monitor); }
        catch (Throwable e) { log.error("Error closing tunnel", e); }
        finally { tunnelHandler = null; }
    }
}
```

Called both from the failure path above and from the normal disconnect path. DBeaver
does **not** guarantee `closeTunnel` is called exactly once per `initializeHandler` call
(e.g. connect-failure paths, repeated "Test Connection" clicks each create a *new*
`DBWTunnel` instance via `createHandler()` — instances are not reused/pooled). Therefore:

- Each `DBWTunnel` instance in this plugin owns exactly one `kubectl` process; it is
  never asked to manage more than one tunnel.
- `closeTunnel()` must be safe to call on an instance whose `initializeHandler()` never
  ran, and safe to call twice (idempotent close), because DBeaver's own error path can
  call it after a failed/partial `initializeHandler()`.

`invalidateHandler()` is called around connection revalidation/ping cycles
(`DBCInvalidatePhase`); SSH uses it to detect a dead session and self-heal or close.
This plugin does not attempt tunnel revalidation in v1 (see `docs/architecture.md` for
the resulting limitation): it implements `invalidateHandler()` as a liveness check of the
`kubectl` process only, with no reconnect — automatic recovery is intentionally out of
scope.

## 5. UI configuration panel

A **second, independent** extension point supplies the settings UI:
`org.jkiss.dbeaver.ui.propertyConfigurator`
(`plugins/org.jkiss.dbeaver.ui/schema/org.jkiss.dbeaver.ui.propertyConfigurator.exsd`).
It maps the handler's **implementation class** (not its extension id) to a UI class:

```xml
<!-- org.jkiss.dbeaver.net.ssh.ui/plugin.xml -->
<extension point="org.jkiss.dbeaver.ui.propertyConfigurator">
    <propertyConfigurator class="org.jkiss.dbeaver.model.net.ssh.SSHTunnelImpl"
                           uiClass="org.jkiss.dbeaver.ui.net.ssh.SSHTunnelConfiguratorUI"/>
</extension>

<!-- org.jkiss.dbeaver.core/plugin.xml -->
<extension point="org.jkiss.dbeaver.ui.propertyConfigurator">
    <propertyConfigurator class="org.jkiss.dbeaver.model.impl.net.SocksProxyImpl"
                           uiClass="org.jkiss.dbeaver.ui.dialogs.net.SocksProxyConfiguratorUI"/>
</extension>
```

The UI class implements `org.jkiss.dbeaver.ui.IObjectPropertyConfigurator<OBJECT, SETTINGS>`
(`plugins/org.jkiss.dbeaver.ui`):

```java
public interface IObjectPropertyConfigurator<OBJECT, SETTINGS> {
    void createControl(Composite parent, OBJECT object, Runnable propertyChangeListener);
    void loadSettings(SETTINGS settings);
    void saveSettings(SETTINGS settings);
    void resetSettings(SETTINGS settings);
    boolean isComplete();
    default String getErrorMessage() { return null; }
}
```

For a network handler, `SETTINGS` is always `DBWHandlerConfiguration`. Hosting code is
`org.jkiss.dbeaver.ui.dialogs.connection.ConnectionPageNetworkHandler`
(`org.jkiss.dbeaver.ui.editors.connection`): it looks up the configurator by
`UIPropertyConfiguratorRegistry.getInstance().getDescriptor(implName)` where `implName`
is the handler's implementation class name — **if no `propertyConfigurator` is
registered for the handler's `handlerClass`, the connection wizard silently skips the
handler entirely (no tab is shown)**. Registering this extension is therefore mandatory,
not optional, for the handler to be usable from the UI.

**Important — the "Enable" checkbox is not something the configurator draws.**
`ConnectionPageSettings` builds one checkable tab per registered, driver-applicable
`NetworkHandlerDescriptor`; the tab's own checkbox toggles
`handlerConfiguration.setEnabled(...)`. `IObjectPropertyConfigurator.createControl()`
only has to render the handler-specific *fields* (kubectl path, context, namespace,
etc.) — the SSH and SOCKS configurators do not draw their own enable checkbox, and this
plugin follows the same pattern.

`AbstractObjectPropertyConfigurator<OBJECT, SETTINGS>` (`org.jkiss.dbeaver.ui`) is an
optional convenience base adding `getEditIntention()`/`setEditIntention(...)`
(`DBPConnectionEditIntention`, from `org.jkiss.dbeaver.registry.configurator`). Using it
would pull `org.jkiss.dbeaver.registry` onto the UI module's compile classpath for a
feature (credentials-only edit intention) this plugin doesn't need in v1; the UI module
therefore implements `IObjectPropertyConfigurator` directly to keep its dependency
surface to `org.jkiss.dbeaver.model` + `org.jkiss.dbeaver.ui` + SWT only.

## 6. Logging

`org.jkiss.dbeaver.Log` (exported from the `org.jkiss.dbeaver.model` bundle), used
exactly like SLF4J: `private static final Log log = Log.getLog(MyClass.class);` then
`log.debug/info/warn/error(...)`. All DBeaver code uses this instead of
`System.out`/`java.util.logging`; this plugin does the same. No secrets (kubeconfig
contents, tokens) are ever passed to `log.*`.

## 7. Packaging / build mechanism for an *external* (non-monorepo) plugin

DBeaver Community Edition itself is built with **Apache Maven + Eclipse Tycho**
(`pom.xml` at the repo root, `packaging=eclipse-plugin` per bundle, features under
`features/`, products under `product/`). Its root `pom.xml` declares a parent
`com.dbeaver.common:com.dbeaver.common.main` resolved via `relativePath
../dbeaver-common/pom.xml` — a sibling repository that is not published and not part of
a normal checkout (building the full product requires cloning multiple sibling
`dbeaver/*` repos side by side and running `mvn package -f product/aggregate/pom.xml
...`). Reusing DBeaver's own Tycho reactor to build a third-party, out-of-tree plugin is
therefore impractical here — it would require non-public sibling repos this project
cannot access, not a technical API restriction.

Two public sources make a Tycho-free build possible instead:

1. **DBeaver Community publishes a real p2 update site** at
   `https://dbeaver.io/update/latest/` (this is what DBeaver's own in-app "Check for
   updates" uses). It is a standard p2 repository (`content.jar`, `artifacts.jar`,
   `plugins/`, `features/`) containing every `org.jkiss.dbeaver.*` and `org.jkiss.*`
   bundle DBeaver ships, including `org.jkiss.dbeaver.model`, `org.jkiss.dbeaver.registry`,
   `org.jkiss.dbeaver.ui`, `org.jkiss.dbeaver.ui.editors.connection`,
   `org.jkiss.dbeaver.net.ssh` and `org.jkiss.utils`. It does **not** carry the base
   Eclipse Platform/RCP bundles (`org.eclipse.swt`, `org.eclipse.jface`,
   `org.eclipse.core.runtime`, `org.eclipse.ui.workbench`, ...) — those ship inside the
   DBeaver installer itself (bundled Eclipse delta pack), not through this update site.
2. **The Eclipse Platform bundles DBeaver needs are separately published to Maven
   Central** under groupId `org.eclipse.platform`, including `org.eclipse.swt`,
   `org.eclipse.swt.win32.win32.x86_64`, `org.eclipse.jface`, `org.eclipse.core.runtime`,
   `org.eclipse.equinox.common`, `org.eclipse.equinox.registry`, `org.eclipse.osgi`,
   `org.eclipse.ui.workbench`, `org.eclipse.core.commands`, `org.eclipse.core.jobs`, and
   more — plain jars, no p2 metadata needed to consume them from Maven.

This plugin is built with **plain Apache Maven** (via Maven Wrapper, no Tycho, no p2
resolution at build time), producing standard OSGi bundle jars with
`biz.aQute.bnd:bnd-maven-plugin` (computes `Import-Package`/`Export-Package` from actual
bytecode — far less error-prone than a hand-written `MANIFEST.MF`). The handful of
`org.jkiss.dbeaver.*` jars this plugin compiles against are downloaded once, at build
time, straight from the public p2 site above and installed into the local Maven
repository by a small bootstrap module (`buildtools/dbeaver-deps`) that runs first in the
reactor; everything else (Eclipse Platform, JUnit 5) resolves normally from Maven
Central. Runtime resolution is unaffected by any of this: once installed into a running
DBeaver instance, the plugin's `Import-Package` entries are satisfied by that instance's
own bundles, exactly like every other DBeaver plugin — Maven Central is only used to
obtain **compile-time** stand-ins for the Eclipse Platform API.

A faithful Tycho build would instead require either (a) the private sibling repositories
DBeaver's own Tycho reactor depends on, which are unavailable, or (b) hand-assembling an
equivalent `.target` file against the same two sources above, at materially more
build-time complexity (p2 metadata generation, mirroring, resolver quirks) for no
behavioural difference in the produced bundle. See `docs/architecture.md` for the
resulting module layout and `docs/troubleshooting.md`/README for the exact build command.

**Manual dropins installation does not work with this DBeaver build.** Copying the built
plugin jars into DBeaver's `dropins/` folder does not get them picked up: dropins are not
a supported install path for a plain-jar layout in this installation.
`org.eclipse.equinox.p2.reconciler.dropins` is present in the product's `plugins/`
folder, but nothing in the product's startup sequence triggers it for plain-jar dropins —
no p2 profile snapshot or `bundles.info` entry is ever produced for jars dropped into
`dropins/`. See `docs/troubleshooting.md` and the README's "Installation" section for the
supported install procedure.

Installation instead goes through a real p2 repository. `scripts/Install-DBeaverPlugin.ps1`
generates a local p2 metadata repository from the built jars using the p2 publisher
application that ships inside DBeaver's own `plugins/` folder
(`org.eclipse.equinox.p2.publisher(.eclipse)`), then installs from it with the p2 director
application (`org.eclipse.equinox.p2.director(.app)`), both run headlessly via DBeaver's
own console launcher (`dbeaverc.exe -application <app id> ...`) — no external tooling
needed, since a full DBeaver install already bundles the entire p2 toolchain (publisher,
director, repository/artifact/metadata support). This directly updates the running
installation's `bundles.info` and p2 profile, exactly as a "real" install would.

DBeaver's own **Help → Install New Software...** wizard is an alternative to the script,
pointed at a hosted or local p2 repository — but the wizard only lists p2 installable
units of type `group` (Features/Categories); pointed at a repository containing only
plain-bundle IUs (no Feature, no Category) it reports "There are no items available"
regardless of the "Group items by category" checkbox state. Making the wizard usable
therefore requires a p2 category grouping the two bundles (see §9).

## 8. Compatibility / risk notes

- **Internal/unstable APIs relied upon:** none of `DBWNetworkHandler`, `DBWTunnel`,
  `DBWForwarder`, `DBWHandlerConfiguration`, `DBWHandlerType`, `DBRProgressMonitor`,
  `DBException`, `Log`, `IObjectPropertyConfigurator` are marked `@Internal` or excluded
  from `Export-Package`; they are the same public extension points DBeaver's own SSH/SOCKS
  handlers use, so the primary compatibility risk is ordinary API evolution between
  DBeaver releases, not usage of a deliberately-private class.
- **Version coupling:** the plugin is compiled against whatever bundle versions are
  current on `https://dbeaver.io/update/latest/` at build time (captured in
  `buildtools/dbeaver-deps/pom.xml`). A future DBeaver release could change
  `DBWNetworkHandler`/`DBWTunnel` method signatures (it is a `2.x`-versioned bundle, so
  breaking changes are possible between majors); this plugin has no forward-compatibility
  shim for that and would need re-compiling against a newer snapshot of the same jars.
- **`DBWForwarder.matchesParameters`** is implemented to always return `false` (see §2) —
  a deliberate, documented simplification, not a bug: unlike SSH/SOCKS, a Kubernetes
  tunnel's "remote endpoint" is a `(context, namespace, resource, port)` tuple, not a
  reusable `host:port` pair, so the SSH-style host/port matching heuristic does not apply.
- **`FEATURE_SSH_TUNNELING` gate:** harmless for Community Edition desktop use (§1);
  documented here in case this plugin is ever loaded inside a distributed/team DBeaver
  product where that feature flag could hide all tunnel-type handlers, including this
  one.

## 9. Closing the "Install New Software..." wizard gap with a p2 category (not Tycho)

The two bundles are built with plain Maven + `bnd-maven-plugin`, not Tycho (§7), and can
be installed via DBeaver's own bundled p2 publisher/director, run headlessly
(`scripts/Install-DBeaverPlugin.ps1`). Making DBeaver's **Help → Install New
Software...** wizard usable as well requires a p2 **category**, since the wizard only
lists installable units of type `group` (Features/Categories) and the two plain bundle
IUs on their own have no grouping (§7).

A full Tycho conversion (`eclipse-plugin`/`eclipse-feature`/`eclipse-repository`
packaging, a Tycho target platform) would not close that gap on its own: the wizard's
restriction is purely "no `group`-type IU exists", unrelated to how the bundles are
built. It would also need a second p2 source for the base Eclipse Platform bundles
(DBeaver's own p2 site only carries `org.jkiss.*`, per §7), on top of DBeaver's own
private-sibling-repo Tycho reactor problem — so Tycho buys nothing here.

Instead, a `repository/` Maven module (packaging `pom`) wraps the two **unchanged,
already-built** bundle jars into a real p2 site using `org.reficio:p2-maven-plugin` (goal
`p2:site`, bound to the `package` phase) — a plain Maven plugin, not Tycho, needing no
external DBeaver installation at build time. Its `category.xml` (copied from the
plugin's own default template, just with a project-specific label) declares one p2
**category** — `org.eclipse.equinox.p2.type.category=true` — containing both bundles. A
category is a `group`-type IU exactly like an Eclipse Feature for the wizard's purposes,
so this alone closes the gap without a `feature.xml`/Eclipse Feature layer.

`.\mvnw.cmd clean verify` produces
`repository/target/repository/{content.jar,artifacts.jar,plugins/*.jar}`; unzipping
`content.jar` shows a `unit` with `org.eclipse.equinox.p2.type.category=true`,
`org.eclipse.equinox.p2.name='DBeaver Kubernetes Extensions'`, and two `<required>`
entries pinning both bundle IUs at their exact built versions.

**Known cosmetic limitation, not fixed:** the category unit's own `id` (and its
self-`provided` IU reference) comes out as
`file:/<absolute-build-path>/repository/category.xml.io.github.nikvoronin.dbeaver.k8s.category`
instead of a clean `io.github.nikvoronin.dbeaver.k8s.category`. This is inherent to
`org.eclipse.equinox.p2.publisher.CategoryPublisher`'s legacy `<site>`/`<category-def>`
schema (no `id` attribute distinct from `name`; the tool derives a globally-unique id by
prefixing with the `category.xml` URI it was invoked with) — it is not a p2-maven-plugin
misconfiguration, and it does not affect the wizard's display (which reads the `name`
property) or the category's `requires` on the two bundles. It does mean the raw id embeds
the local build machine's path and will differ between rebuilds at a different checkout
path.

Installing via a bundled p2 director pointed directly at `repository/target/repository`
has not been tested against a real DBeaver installation; only the raw p2 metadata was
inspected.

The plugin has two install paths: DBeaver's **Help → Install New Software...** wizard
pointed at a hosted p2 repository, or `scripts/Install-DBeaverPlugin.ps1` run headlessly.
A dropins-zip assembly module was removed from the build entirely, since manual dropins
installation does not work (§7).

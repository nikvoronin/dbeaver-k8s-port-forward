# Build prerequisites

What a clean machine needs before `.\mvnw.cmd clean verify` succeeds, and what is only needed
for optional extra steps (installing into a real DBeaver, or the manual Kubernetes integration
test) — not for the build itself.

## Required for the build itself

| Requirement | Details |
| --- | --- |
| **JDK 21 or newer** | The build compiles with `--release 21` (`maven.compiler.release` in the root `pom.xml`). This project is developed and tested against **JDK 25 (Eclipse Temurin/Adoptium)** — that's the safe baseline to install if you have no preference. Get it from your OS package manager or https://adoptium.net/. Set `JAVA_HOME` to point at it. |
| **Git** | To clone this repository. |
| **Outbound network access**, to: | |
| — `repo.maven.apache.org` / `repo1.maven.org` (Maven Central) | Everything except the three `org.jkiss.*` jars below: Maven Wrapper's own download of Maven 3.9.16, all plugin/dependency jars (bnd, JUnit 5, Eclipse Platform SWT/core.runtime, `org.reficio:p2-maven-plugin` and everything it pulls in). |
| — `https://dbeaver.io/update/latest/` | DBeaver Community's own p2 update site — the only source for `org.jkiss.dbeaver.model`, `org.jkiss.dbeaver.ui`, `org.jkiss.utils` (there is no Maven Central distribution of these). Downloaded once by the `buildtools/dbeaver-deps` module and installed into your local `.m2` repo. **Caveat:** that site only keeps its most recently published build, so the exact jar versions pinned in the root `pom.xml` will eventually 404; see the comment above `<dbeaver.model.version>` there for how to refresh them. |

Nothing else is required. In particular:

- **No globally installed Maven** — Maven Wrapper (`mvnw.cmd` / `mvnw` / `.mvn/wrapper/`) is
  committed to the repo and downloads the pinned Maven version itself on first run.
- **No DBeaver installation** — the build never touches a real DBeaver install; it only compiles
  against the three `org.jkiss.*` jars above (plus Eclipse Platform jars from Maven Central).
- **No `kubectl` binary and no Kubernetes cluster** — the unit tests replace `kubectl` with a fake
  Java-based launcher (`FakeKubectlLauncher`/`FakeKubectlMain`); nothing in the build shells out
  to a real `kubectl`.
- **No Eclipse Tycho / p2 target platform** — this project deliberately does not build with Tycho;
  see `docs/dbeaver-api-notes.md` section 7.

## Clean-machine checklist

```powershell
# 1. Install a JDK (21+, JDK 25 is the tested baseline) and set JAVA_HOME to it.
# 2. Clone the repository.
git clone <this repo's URL>
cd dbeaver-k8s-port-forward

# 3. Build. This alone downloads every other dependency listed above.
.\mvnw.cmd clean verify
```

Expected result: `BUILD SUCCESS`, 49 unit tests passing, and these artifacts produced:

```text
plugins/io.github.nikvoronin.dbeaver.k8s/target/io.github.nikvoronin.dbeaver.k8s_<version>.jar
plugins/io.github.nikvoronin.dbeaver.k8s.ui/target/io.github.nikvoronin.dbeaver.k8s.ui_<version>.jar
repository/target/repository/{content.jar,artifacts.jar,plugins/*.jar}
```

If the build fails while downloading from `dbeaver.io/update/latest/` with a 404, that's the
"only keeps the latest build" caveat above, not a missing prerequisite — update the pinned
versions per the instructions in the root `pom.xml`.

## Only needed for optional extra steps (not the build)

| Requirement | Only needed for |
| --- | --- |
| A real DBeaver Community Edition installation | Actually installing/running the plugin — either through `repository/target/repository` via **Help → Install New Software...**, or via `scripts\Install-DBeaverPlugin.ps1` (see README "Installation"). |
| Windows PowerShell 5.1+ (or PowerShell 7+) | `scripts\Install-DBeaverPlugin.ps1` only. The build itself (`mvnw.cmd`) runs fine from `cmd.exe` or a POSIX shell too. |
| A Kubernetes cluster + a real `kubectl` binary | The manual end-to-end test in `docs/integration-test.md` only — never the automated build/test suite. |
| A local clone of `dbeaver/dbeaver` under `.reference/dbeaver` | Only if you're re-doing the API research in `docs/dbeaver-api-notes.md` against a newer DBeaver source; not read by the build. |

#Requires -Version 5.1
<#
.SYNOPSIS
    Builds this plugin and installs/updates it in a real DBeaver installation, or uninstalls it.

.DESCRIPTION
    DBeaver's "dropins" folder is not reliably picked up by every DBeaver desktop distribution
    (confirmed unreliable on a real DBeaver 26.2.x .exe build — see docs/dbeaver-api-notes.md
    section 7), and DBeaver's own "Help -> Install New Software..." wizard refuses to list plain
    OSGi bundles that aren't wrapped in an Eclipse Feature/Category. The only method found to
    work reliably is running the p2 tooling that ships inside DBeaver's own installation
    headlessly, via its console launcher (dbeaverc.exe):

      1. org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher - turns the two built
         plugin jars into a throwaway local p2 metadata+artifact repository.
      2. org.eclipse.equinox.p2.director - installs (or, with -uninstallIU, removes) bundles
         from that repository into the real DBeaver installation's p2 profile.

    This script automates both steps. Because this project's version string never changes
    between builds (1.0.0-SNAPSHOT), a plain re-install would be treated as a no-op by the
    resolver if the "same" version is already installed — so every (re)install first uninstalls
    whatever is currently there, then installs the freshly built jars. That makes this script's
    default mode work equally well as a first-time install and as a "push my latest changes"
    update.

.PARAMETER DBeaverHome
    Path to the DBeaver installation directory (the one containing dbeaver.exe / dbeaverc.exe).

.PARAMETER SkipBuild
    Skip the "mvnw.cmd clean verify" build step and install whatever jars are already present
    under plugins\*\target. Useful when iterating on the install step itself.

.PARAMETER Uninstall
    Only uninstall the plugin from the given DBeaver installation; do not build or install
    anything.

.PARAMETER Os
    p2 "-p2.os" value for the target DBeaver installation. Defaults to win32.

.PARAMETER Ws
    p2 "-p2.ws" value for the target DBeaver installation. Defaults to win32.

.PARAMETER Arch
    p2 "-p2.arch" value for the target DBeaver installation. Defaults to x86_64.

.EXAMPLE
    .\scripts\Install-DBeaverPlugin.ps1 -DBeaverHome C:\app\DBeaver
    Builds the plugin and installs/updates it in the given DBeaver installation.

.EXAMPLE
    .\scripts\Install-DBeaverPlugin.ps1 -DBeaverHome C:\app\DBeaver -SkipBuild
    Reinstalls whatever is already built, without rebuilding.

.EXAMPLE
    .\scripts\Install-DBeaverPlugin.ps1 -DBeaverHome C:\app\DBeaver -Uninstall
    Removes the plugin from the given DBeaver installation.

.NOTES
    DBeaver must be closed before running this script in any mode: it modifies the running
    installation's shared configuration/p2 profile, and the script refuses to proceed if a
    "dbeaver"/"dbeaverc" process is found running.

    On Linux/macOS, pass -Os linux -Ws gtk or -Os macosx -Ws cocoa respectively, and note that
    the DBeaver console launcher is named "dbeaverc" (no .exe) there — this script assumes
    Windows (per this project's primary target platform) and looks for "dbeaverc.exe" only.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DBeaverHome,

    [switch]$SkipBuild,

    [switch]$Uninstall,

    [string]$Os = 'win32',
    [string]$Ws = 'win32',
    [string]$Arch = 'x86_64'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot

$CoreArtifactId = 'io.github.nikvoronin.dbeaver.k8s'
$UiArtifactId = 'io.github.nikvoronin.dbeaver.k8s.ui'

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-DBeaverConsole {
    param([string]$InstallDir)
    $exe = Join-Path $InstallDir 'dbeaverc.exe'
    if (-not (Test-Path $exe)) {
        throw ("dbeaverc.exe not found under '{0}'. -DBeaverHome must point at the DBeaver " +
            "installation directory (the one containing dbeaver.exe/dbeaverc.exe).") -f $InstallDir
    }
    return $exe
}

function Assert-DBeaverNotRunning {
    $procs = Get-Process -Name 'dbeaver', 'dbeaverc' -ErrorAction SilentlyContinue
    if ($procs) {
        $pids = ($procs | Select-Object -ExpandProperty Id) -join ', '
        throw ("DBeaver appears to be running (PID {0}). Close it before running this script " +
            "-- it modifies the running installation's shared p2 profile/configuration.") -f $pids
    }
}

function Invoke-P2Application {
    param(
        [Parameter(Mandatory = $true)][string]$DbeaverConsole,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Description,
        [switch]$AllowFailure
    )
    Write-Host "    $DbeaverConsole $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $DbeaverConsole @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        if ($AllowFailure) {
            Write-Host "    ($Description exited with code $exitCode -- treated as informational, continuing)" -ForegroundColor Yellow
        }
        else {
            throw "$Description failed with exit code $exitCode. See dbeaverc output above."
        }
    }
    return $exitCode
}

function Repair-JavaHomeIfBroken {
    # Works around a broken machine-wide JAVA_HOME (seen on this project's dev machine: it was
    # set to a literal, non-existent placeholder path). Only touches $env:JAVA_HOME for this
    # script's own process -- never modifies the persistent user/system environment variable.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return
    }
    $javaCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if (-not $javaCommand) {
        throw ("JAVA_HOME is not set to a valid JDK (JAVA_HOME='{0}'), and no java.exe was " +
            "found on PATH either. Install a JDK 21+ or fix JAVA_HOME before running this " +
            "script.") -f $env:JAVA_HOME
    }
    $detectedHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
    Write-Host ("    JAVA_HOME ('{0}') is invalid; using '{1}' (found via PATH) for this run only." -f $env:JAVA_HOME, $detectedHome) -ForegroundColor Yellow
    $env:JAVA_HOME = $detectedHome
}

function Get-BuiltJar {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactId
    )
    $targetDir = Join-Path $RepoRoot ('plugins\{0}\target' -f $ArtifactId)
    $pattern = '{0}_*.jar' -f $ArtifactId
    $jar = Get-ChildItem -Path $targetDir -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|tests)\.jar$' } |
        Select-Object -First 1
    if (-not $jar) {
        throw ("Could not find a built jar matching '{0}' under '{1}'. Run without -SkipBuild, " +
            "or build manually first (.\mvnw.cmd clean verify).") -f $pattern, $targetDir
    }
    return $jar
}

Write-Step "Validating DBeaver installation at '$DBeaverHome'"
$dbeaverc = Resolve-DBeaverConsole -InstallDir $DBeaverHome

Write-Step 'Checking DBeaver is not running'
Assert-DBeaverNotRunning

$scratch = Join-Path $env:TEMP 'dbeaver-k8s-plugin-deploy'

if ($Uninstall) {
    Write-Step "Uninstalling $CoreArtifactId and $UiArtifactId"
    Invoke-P2Application -DbeaverConsole $dbeaverc -Description 'Uninstall' -Arguments @(
        '-nosplash', '-consoleLog', '-clean',
        '-data', (Join-Path $scratch 'workspace-uninstall'),
        '-application', 'org.eclipse.equinox.p2.director',
        '-destination', "file:$DBeaverHome",
        '-profile', 'DefaultProfile',
        '-uninstallIU', "$CoreArtifactId,$UiArtifactId",
        '-p2.os', $Os, '-p2.ws', $Ws, '-p2.arch', $Arch
    )

    Write-Host ''
    Write-Host "Uninstalled. Restart DBeaver -- the 'Kubernetes' network handler will be gone." -ForegroundColor Green
    return
}

if (-not $SkipBuild) {
    Write-Step 'Building the plugin (mvnw.cmd clean verify)'
    Repair-JavaHomeIfBroken
    $mvnw = Join-Path $RepoRoot 'mvnw.cmd'
    if (-not (Test-Path $mvnw)) {
        throw "Maven Wrapper not found at '$mvnw'."
    }
    & $mvnw clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed (mvnw.cmd exited with code $LASTEXITCODE). Fix the build before installing."
    }
}
else {
    Write-Step 'Skipping build (-SkipBuild)'
}

Write-Step 'Locating built plugin jars'
$coreJar = Get-BuiltJar -ArtifactId $CoreArtifactId
$uiJar = Get-BuiltJar -ArtifactId $UiArtifactId
Write-Host "    Core: $($coreJar.FullName)"
Write-Host "    UI:   $($uiJar.FullName)"

Write-Step 'Preparing scratch p2 source/repo folders'
if (Test-Path $scratch) {
    Remove-Item -Path $scratch -Recurse -Force
}
$p2Source = Join-Path $scratch 'p2-source\plugins'
$p2Repo = Join-Path $scratch 'p2-repo'
New-Item -Path $p2Source -ItemType Directory -Force | Out-Null
Copy-Item -Path $coreJar.FullName -Destination $p2Source
Copy-Item -Path $uiJar.FullName -Destination $p2Source

Write-Step 'Generating a local p2 repository from the plugin jars'
Invoke-P2Application -DbeaverConsole $dbeaverc -Description 'p2 metadata generation' -Arguments @(
    '-nosplash', '-consoleLog', '-clean',
    '-data', (Join-Path $scratch 'workspace-publish'),
    '-application', 'org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher',
    '-metadataRepository', "file:$p2Repo",
    '-artifactRepository', "file:$p2Repo",
    '-source', (Join-Path $scratch 'p2-source'),
    '-publishArtifacts'
)

Write-Step 'Removing any previously installed version (safe no-op on a first install)'
Invoke-P2Application -DbeaverConsole $dbeaverc -Description 'Uninstall of previous version' -AllowFailure -Arguments @(
    '-nosplash', '-consoleLog', '-clean',
    '-data', (Join-Path $scratch 'workspace-uninstall'),
    '-application', 'org.eclipse.equinox.p2.director',
    '-destination', "file:$DBeaverHome",
    '-profile', 'DefaultProfile',
    '-uninstallIU', "$CoreArtifactId,$UiArtifactId",
    '-p2.os', $Os, '-p2.ws', $Ws, '-p2.arch', $Arch
)

Write-Step 'Installing the freshly built plugin'
Invoke-P2Application -DbeaverConsole $dbeaverc -Description 'Install' -Arguments @(
    '-nosplash', '-consoleLog', '-clean',
    '-data', (Join-Path $scratch 'workspace-install'),
    '-application', 'org.eclipse.equinox.p2.director',
    '-repository', "file:$p2Repo",
    '-destination', "file:$DBeaverHome",
    '-profile', 'DefaultProfile',
    '-profileProperties', 'org.eclipse.update.install.features=true',
    '-installIU', "$CoreArtifactId,$UiArtifactId",
    '-p2.os', $Os, '-p2.ws', $Ws, '-p2.arch', $Arch
)

Write-Host ''
Write-Host "Installed successfully. Start DBeaver -- the 'Kubernetes' tab should appear in any connection's settings." -ForegroundColor Green

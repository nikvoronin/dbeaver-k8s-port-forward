#Requires -Version 5.1
<#
.SYNOPSIS
    Verifies the GitHub build-provenance attestation of a release zip or plugin jar.

.DESCRIPTION
    Release artifacts are built by .github/workflows/release.yml, which signs a GitHub artifact
    attestation (SLSA build provenance, Sigstore) for the release zip and for every plugin jar.
    This script wraps `gh attestation verify` and requires that the attestation was produced by
    that exact workflow file of the given repository.

    For a .zip the script verifies the zip itself, then extracts it to a temporary folder and
    verifies every jar inside (these are the jars DBeaver will actually install). A plain .jar is
    verified on its own.

    Passing a successful verification means "these bytes were built by this workflow from this
    repository". It does not audit the code and it is not a jar signature, so DBeaver will still
    show its "Unsigned" prompt on install.

    Requires the GitHub CLI (gh 2.49 or newer) and an authenticated session: run `gh auth login`
    once, or set the GH_TOKEN environment variable. Any GitHub account works.

.PARAMETER Path
    One or more files to verify: the release zip and/or plugin jars.

.PARAMETER Tag
    Download dbeaver-k8s-port-forward_<Tag>.zip from that published release and verify it
    (together with its jars). Draft releases cannot be downloaded this way.

.PARAMETER Repo
    GitHub repository that built the artifacts, as owner/name. Defaults to the upstream project;
    change it only to verify artifacts from a fork.

.EXAMPLE
    .\scripts\Verify-Attestation.ps1 .\dbeaver-k8s-port-forward_1.2.0.zip
    Verifies a zip downloaded from the releases page and every jar inside it.

.EXAMPLE
    .\scripts\Verify-Attestation.ps1 -Tag 1.2.0
    Downloads the release zip for tag 1.2.0 and verifies it and its jars.

.EXAMPLE
    .\scripts\Verify-Attestation.ps1 io.github.nikvoronin.dbeaver.k8s_1.2.0.jar
    Verifies a single jar.

.NOTES
    Exit code: 0 = everything verified, 1 = at least one file failed verification (do not install
    it), 2 = prerequisites missing or bad arguments.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Path = @(),

    [string]$Tag,

    [string]$Repo = 'nikvoronin/dbeaver-k8s-port-forward'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SignerWorkflow = "$Repo/.github/workflows/release.yml"
$script:Verified = 0
$script:Failed = 0
$TempDirs = New-Object System.Collections.Generic.List[string]

# Runs gh without letting its stderr turn into a terminating PowerShell error (Windows
# PowerShell 5.1 does that for redirected native stderr under $ErrorActionPreference = 'Stop').
function Invoke-Gh {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & gh @args 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
        return [pscustomobject]@{ ExitCode = $code; Output = ($lines -join [Environment]::NewLine) }
    }
    finally {
        $ErrorActionPreference = $prev
    }
}

function New-TempDir {
    $dir = Join-Path ([IO.Path]::GetTempPath()) ('verify-attestation-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $dir | Out-Null
    $TempDirs.Add($dir)
    return $dir
}

function Test-Attestation([string]$File) {
    $name = Split-Path -Leaf $File
    $r = Invoke-Gh attestation verify $File --repo $Repo --signer-workflow $SignerWorkflow
    if ($r.ExitCode -eq 0) {
        Write-Host "[ OK ] $name" -ForegroundColor Green
        $script:Verified++
    }
    else {
        Write-Host "[FAIL] $name" -ForegroundColor Red
        Write-Host $r.Output
        $script:Failed++
    }
}

function Test-Target([string]$File) {
    if (-not (Test-Path -LiteralPath $File -PathType Leaf)) {
        Write-Host "[FAIL] $File : file not found" -ForegroundColor Red
        $script:Failed++
        return
    }
    $File = (Resolve-Path -LiteralPath $File).ProviderPath
    Test-Attestation $File

    if ([IO.Path]::GetExtension($File) -ne '.zip') { return }

    $extractDir = New-TempDir
    try {
        Expand-Archive -LiteralPath $File -DestinationPath $extractDir -Force
    }
    catch {
        Write-Host "[FAIL] $(Split-Path -Leaf $File) : cannot extract: $($_.Exception.Message)" -ForegroundColor Red
        $script:Failed++
        return
    }
    $jars = @(Get-ChildItem -LiteralPath $extractDir -Recurse -Filter '*.jar' -File)
    if ($jars.Count -eq 0) {
        Write-Host "[FAIL] $(Split-Path -Leaf $File) : no jar files inside, unexpected zip layout" -ForegroundColor Red
        $script:Failed++
        return
    }
    foreach ($jar in $jars) { Test-Attestation $jar.FullName }
}

try {
    if (-not $Tag -and $Path.Count -eq 0) {
        Write-Host 'Nothing to verify. Pass a zip/jar path or -Tag <tag>. See: Get-Help .\scripts\Verify-Attestation.ps1 -Full'
        exit 2
    }

    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Write-Host 'GitHub CLI (gh) was not found on PATH. Install it from https://cli.github.com/ (version 2.49 or newer).'
        exit 2
    }
    if ((Invoke-Gh attestation --help).ExitCode -ne 0) {
        Write-Host 'This gh has no "attestation" command. Upgrade the GitHub CLI to 2.49 or newer.'
        exit 2
    }
    if ((Invoke-Gh auth status).ExitCode -ne 0) {
        Write-Host 'gh is not authenticated. Run "gh auth login" once, or set the GH_TOKEN environment variable (any GitHub account works).'
        exit 2
    }

    $targets = New-Object System.Collections.Generic.List[string]
    foreach ($p in $Path) { $targets.Add($p) }

    if ($Tag) {
        $downloadDir = New-TempDir
        $asset = "dbeaver-k8s-port-forward_$Tag.zip"
        Write-Host "Downloading $asset from $Repo ..."
        $r = Invoke-Gh release download $Tag --repo $Repo --pattern $asset --dir $downloadDir
        $zip = Join-Path $downloadDir $asset
        if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $zip)) {
            Write-Host $r.Output
            Write-Host "Could not download '$asset' from release '$Tag' (is it published, not a draft?)."
            exit 2
        }
        $targets.Add($zip)
    }

    foreach ($t in $targets) { Test-Target $t }

    Write-Host ''
    Write-Host "Verified: $($script:Verified), failed: $($script:Failed)"
    if ($script:Failed -gt 0) {
        Write-Host 'At least one file did NOT verify. Do not install it.' -ForegroundColor Red
        exit 1
    }
    Write-Host 'All files were built by the expected workflow.' -ForegroundColor Green
    exit 0
}
finally {
    foreach ($d in $TempDirs) {
        Remove-Item -LiteralPath $d -Recurse -Force -ErrorAction SilentlyContinue
    }
}

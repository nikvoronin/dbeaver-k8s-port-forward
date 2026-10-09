#!/usr/bin/env bash
# Verifies the GitHub build-provenance attestation of a release zip or plugin jar.
# Works with the stock macOS bash 3.2 and with Linux; needs gh, unzip.
#
# Release artifacts are built by .github/workflows/release.yml, which signs a GitHub artifact
# attestation (SLSA build provenance, Sigstore) for the release zip and for every plugin jar.
# This script wraps `gh attestation verify` and requires that the attestation was produced by
# that exact workflow file of the given repository.
#
# For a .zip it verifies the zip itself, then extracts it to a temporary folder and verifies
# every jar inside (these are the jars DBeaver will actually install). A plain .jar is verified
# on its own.
#
# A successful verification means "these bytes were built by this workflow from this repository".
# It does not audit the code and it is not a jar signature, so DBeaver will still show its
# "Unsigned" prompt on install.
#
# Requires the GitHub CLI (gh 2.49 or newer) and an authenticated session: run `gh auth login`
# once, or export GH_TOKEN. Any GitHub account works.
#
# Usage:
#   scripts/verify-attestation.sh [--repo owner/name] FILE...
#   scripts/verify-attestation.sh [--repo owner/name] --tag TAG
#
#   FILE         release zip and/or plugin jars
#   --tag TAG    download dbeaver-k8s-port-forward_TAG.zip from that published release (not a
#                draft) and verify it together with its jars
#   --repo R     repository that built the artifacts; defaults to the upstream project, change it
#                only to verify artifacts from a fork
#
# Exit code: 0 = everything verified, 1 = at least one file failed verification (do not install
# it), 2 = prerequisites missing or bad arguments.

set -u

REPO="nikvoronin/dbeaver-k8s-port-forward"
TAG=""
FILES=()
TEMP_DIRS=()
VERIFIED=0
FAILED=0

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    echo "Usage:"
    echo "  $0 [--repo owner/name] FILE..."
    echo "  $0 [--repo owner/name] --tag TAG"
}

cleanup() {
    local d
    for d in ${TEMP_DIRS[@]+"${TEMP_DIRS[@]}"}; do
        rm -rf "$d"
    done
}
trap cleanup EXIT

if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; RESET=$'\033[0m'
else
    GREEN=""; RED=""; RESET=""
fi

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --repo)    [ $# -ge 2 ] || { echo "--repo needs a value" >&2; exit 2; }; REPO="$2"; shift 2 ;;
        --tag)     [ $# -ge 2 ] || { echo "--tag needs a value" >&2; exit 2; }; TAG="$2"; shift 2 ;;
        --)        shift; while [ $# -gt 0 ]; do FILES+=("$1"); shift; done ;;
        -*)        echo "Unknown option: $1" >&2; exit 2 ;;
        *)         FILES+=("$1"); shift ;;
    esac
done

if [ -z "$TAG" ] && [ ${#FILES[@]} -eq 0 ]; then
    echo "Nothing to verify. Pass a zip/jar path or --tag TAG (see --help)." >&2
    exit 2
fi

SIGNER_WORKFLOW="$REPO/.github/workflows/release.yml"

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI (gh) was not found on PATH. Install it from https://cli.github.com/ (version 2.49 or newer), e.g. 'brew install gh'." >&2
    exit 2
fi
if ! gh attestation --help >/dev/null 2>&1; then
    echo "This gh has no 'attestation' command. Upgrade the GitHub CLI to 2.49 or newer." >&2
    exit 2
fi
if ! gh auth status >/dev/null 2>&1; then
    echo "gh is not authenticated. Run 'gh auth login' once, or export GH_TOKEN (any GitHub account works)." >&2
    exit 2
fi

new_temp_dir() {
    local d
    d="$(mktemp -d "${TMPDIR:-/tmp}/verify-attestation.XXXXXX")" || exit 2
    TEMP_DIRS+=("$d")
    NEW_TEMP_DIR="$d"
}

verify_one() {
    local file="$1" name out
    name="$(basename "$file")"
    if out="$(gh attestation verify "$file" --repo "$REPO" --signer-workflow "$SIGNER_WORKFLOW" 2>&1)"; then
        echo "[${GREEN} OK ${RESET}] $name"
        VERIFIED=$((VERIFIED + 1))
    else
        echo "[${RED}FAIL${RESET}] $name"
        echo "$out"
        FAILED=$((FAILED + 1))
    fi
}

verify_target() {
    local file="$1" name extract_dir jar count
    name="$(basename "$file")"
    if [ ! -f "$file" ]; then
        echo "[${RED}FAIL${RESET}] $file : file not found"
        FAILED=$((FAILED + 1))
        return
    fi
    verify_one "$file"

    case "$file" in
        *.zip) ;;
        *) return ;;
    esac

    if ! command -v unzip >/dev/null 2>&1; then
        echo "[${RED}FAIL${RESET}] $name : 'unzip' not found, cannot verify the jars inside"
        FAILED=$((FAILED + 1))
        return
    fi
    new_temp_dir
    extract_dir="$NEW_TEMP_DIR"
    if ! unzip -q "$file" -d "$extract_dir"; then
        echo "[${RED}FAIL${RESET}] $name : cannot extract"
        FAILED=$((FAILED + 1))
        return
    fi
    count=0
    while IFS= read -r jar; do
        count=$((count + 1))
        verify_one "$jar"
    done < <(find "$extract_dir" -type f -name '*.jar')
    if [ "$count" -eq 0 ]; then
        echo "[${RED}FAIL${RESET}] $name : no jar files inside, unexpected zip layout"
        FAILED=$((FAILED + 1))
    fi
}

if [ -n "$TAG" ]; then
    asset="dbeaver-k8s-port-forward_${TAG}.zip"
    new_temp_dir
    download_dir="$NEW_TEMP_DIR"
    echo "Downloading $asset from $REPO ..."
    if ! gh release download "$TAG" --repo "$REPO" --pattern "$asset" --dir "$download_dir" \
        || [ ! -f "$download_dir/$asset" ]; then
        echo "Could not download '$asset' from release '$TAG' (is it published, not a draft?)." >&2
        exit 2
    fi
    FILES+=("$download_dir/$asset")
fi

for f in "${FILES[@]}"; do
    verify_target "$f"
done

echo
echo "Verified: $VERIFIED, failed: $FAILED"
if [ "$FAILED" -gt 0 ]; then
    echo "${RED}At least one file did NOT verify. Do not install it.${RESET}"
    exit 1
fi
echo "${GREEN}All files were built by the expected workflow.${RESET}"
exit 0

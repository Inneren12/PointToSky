#!/usr/bin/env bash
set -euo pipefail

if ! command -v unzip >/dev/null 2>&1; then
  echo "unzip is required to inspect release artifacts" >&2
  exit 1
fi

TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

check_module() {
  local module=$1
  local output_dir="$module/build/outputs"
  if [[ ! -d "$output_dir" ]]; then
    echo "No build outputs found for module '$module'. Did you run assembleRelease?" >&2
    exit 1
  fi

  mapfile -t artifacts < <(find "$output_dir" -type f \( -name "*.apk" -o -name "*.aab" \))
  if [[ ${#artifacts[@]} -eq 0 ]]; then
    echo "No release artifacts (*.apk or *.aab) found for module '$module' inside $output_dir" >&2
    exit 1
  fi

  local module_tmp="$TMP_ROOT/$module"
  mkdir -p "$module_tmp"

  local found_string=1
  for artifact in "${artifacts[@]}"; do
    local artifact_tmp="$module_tmp/$(basename "$artifact" .apk)"
    artifact_tmp="${artifact_tmp%.aab}"
    rm -rf "$artifact_tmp"
    mkdir -p "$artifact_tmp"
    unzip -qq "$artifact" -d "$artifact_tmp"
    if grep -R "pointtosky" "$artifact_tmp" >/dev/null 2>&1; then
      found_string=0
      break
    fi
  done

  if [[ $found_string -ne 0 ]]; then
    echo "Failed to detect 'pointtosky' string in any release artifact for module '$module'." >&2
    exit 1
  else
    echo "Verified diagnostics strings present in release artifact for module '$module'."
  fi
}

check_module "mobile"
check_module "wear"

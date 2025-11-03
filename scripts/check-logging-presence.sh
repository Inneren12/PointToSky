#!/usr/bin/env bash
set -euo pipefail

shopt -s nullglob

artifacts=(
  "mobile/build/outputs/apk/release/*.apk"
  "mobile/build/outputs/bundle/release/*.aab"
  "wear/build/outputs/apk/release/*.apk"
  "wear/build/outputs/bundle/release/*.aab"
)

found_any=false

for pattern in "${artifacts[@]}"; do
  for artifact in $pattern; do
    found_any=true
    echo "Inspecting $artifact for 'pointtosky' references"

    asset_entries=$(unzip -Z1 "$artifact" 'assets/*' 2>/dev/null || true)
    asset_has_string=false
    if [[ -n "$asset_entries" ]]; then
      for entry in $asset_entries; do
        if unzip -p "$artifact" "$entry" | strings | grep -qi 'pointtosky'; then
          asset_has_string=true
          break
        fi
      done
    fi

    if [[ "$asset_has_string" == false ]]; then
      dex_entries=$(unzip -Z1 "$artifact" 'classes*.dex' 2>/dev/null || true)
      if [[ -z "$dex_entries" ]]; then
        echo "No inspectable entries found in $artifact" >&2
        exit 1
      fi
      dex_has_string=false
      for entry in $dex_entries; do
        if unzip -p "$artifact" "$entry" | strings | grep -qi 'pointtosky'; then
          dex_has_string=true
          break
        fi
      done
      if [[ "$dex_has_string" == false ]]; then
        echo "Missing 'pointtosky' references in $artifact" >&2
        exit 1
      fi
    fi
  done
done

if [[ "$found_any" == false ]]; then
  echo "No release artifacts were produced. Did assembleRelease run?" >&2
  exit 1
fi

echo "All inspected artifacts contain 'pointtosky' references."

#!/bin/sh
# Remembers the Team picked in Xcode (target VespaNav → Signing & Capabilities) in
# Config/Local.xcconfig, so that `xcodegen generate` keeps it. Run once after picking the Team.
set -eu
cd "$(dirname "$0")/.."
project=VespaNav.xcodeproj/project.pbxproj
if [ ! -f "$project" ]; then
  echo "No $project — run 'xcodegen generate' and pick your Team in Xcode first." >&2
  exit 1
fi
team=$(sed -n 's/.*DEVELOPMENT_TEAM = "\{0,1\}\([A-Za-z0-9]\{1,\}\)"\{0,1\};.*/\1/p' "$project" | head -n 1)
if [ -z "$team" ]; then
  echo "No Team in the project yet: in Xcode open VespaNav → Signing & Capabilities and choose one." >&2
  exit 1
fi
out=Config/Local.xcconfig
touch "$out"
grep -v '^DEVELOPMENT_TEAM' "$out" > "$out.tmp" || true
echo "DEVELOPMENT_TEAM = $team" >> "$out.tmp"
mv "$out.tmp" "$out"
echo "Saved Team $team to $out"

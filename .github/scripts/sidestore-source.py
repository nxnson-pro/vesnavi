#!/usr/bin/env python3
"""Writes the SideStore / AltStore source (apps.json) attached to each GitHub release.

The source URL stays the same for every version:
  https://github.com/<owner>/<repo>/releases/latest/download/apps.json
usage: sidestore-source.py <version> <build> <owner/repo> <ipa> <out.json>
"""
import datetime
import json
import os
import re
import sys

version, build, repo, ipa, out = sys.argv[1:6]
base = f"https://github.com/{repo}"
raw = f"https://raw.githubusercontent.com/{repo}/main"

# Release notes = this version's section of CHANGELOG.md.
changelog = open("CHANGELOG.md", encoding="utf-8").read()
sections = re.split(r"^# ", changelog, flags=re.M)
notes = next((s.split("\n", 1)[1].strip() for s in sections if s.startswith(f"VesNavi {version}")), "")

# Permission texts as shown by iOS (from project.yml).
spec = open("project.yml", encoding="utf-8").read()
privacy = dict(re.findall(r"^\s+(NS\w+UsageDescription): (.+)$", spec, flags=re.M))

source = {
    "name": "VesNavi",
    "identifier": "com.nxnson.vesnavi.source",
    "subtitle": "Vespa TFT navigation (unofficial)",
    "website": base,
    "iconURL": f"{raw}/App/Assets.xcassets/AppIcon.appiconset/AppIcon.png",
    "tintColor": "#1C9E8F",
    "apps": [{
        "name": "VesNavi",
        "bundleIdentifier": "com.nxnson.vespanav",
        "developerName": "nxnson-pro",
        "subtitle": "Turn-by-turn on the Vespa GTS Supertech dashboard",
        "localizedDescription": (
            "Free turn-by-turn navigation on the TFT dashboard of the Vespa GTS Supertech 300 HPE, "
            "plus live engine data, trips, fuel and service reminders. Vietnamese / English. "
            "Unofficial hobby project, not affiliated with Piaggio / Vespa."),
        "iconURL": f"{raw}/App/Assets.xcassets/AppIcon.appiconset/AppIcon.png",
        "tintColor": "#1C9E8F",
        "category": "utilities",
        "screenshots": [f"{raw}/docs/media/en/{n}.gif" for n in ("navigation", "scooter", "trips")],
        "versions": [{
            "version": version,
            "buildVersion": build,
            "date": datetime.date.today().isoformat(),
            "localizedDescription": notes,
            "downloadURL": f"{base}/releases/download/v{version}/VespaNav.ipa",
            "size": os.path.getsize(ipa),
            "minOSVersion": "17.0",
        }],
        "appPermissions": {"entitlements": [], "privacy": privacy},
    }],
    "news": [],
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(source, f, ensure_ascii=False, indent=2)
print(f"{out}: VesNavi {version} ({build}), {len(notes)} chars of notes, {len(privacy)} permissions")

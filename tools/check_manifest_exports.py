#!/usr/bin/env python3
"""SEC-13: regression guard for exported Android components and boot receivers.

Dunder's only intended entry point for other apps is the launcher activity.
Everything else (services, receivers, providers, other activities) must be
explicitly non-exported, and no component may listen for LOCKED_BOOT_COMPLETED
unless it is directBootAware (the boot receiver touches Room/Keystore, which
are unavailable before first unlock). See SEC-13 / RUN-3 in
ForClaude/CLAUDE_BACKEND_PLAN.md.

Checks, for app/src/main/AndroidManifest.xml and every variant manifest:
  1. <service>, <receiver>, <provider> must set android:exported="false"
     (missing counts as a failure; the allowlist below is empty on purpose).
  2. <activity>/<activity-alias> may be exported="true" only if it has a
     MAIN action + LAUNCHER category intent filter. An intent filter with no
     explicit android:exported is a failure (required on Android 12+).
  3. A component declaring the LOCKED_BOOT_COMPLETED action must set
     android:directBootAware="true".

Usage:  python3 tools/check_manifest_exports.py [--root REPO_ROOT]
Stdlib only. Exit codes: 0 ok, 1 violations, 2 usage/setup error.
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
# Component names (as written in the manifest) allowed to be exported. Keep empty
# unless the owner decides a component must be reachable by other apps.
EXPORTED_ALLOWLIST = frozenset()
LOCKED_BOOT = "android.intent.action.LOCKED_BOOT_COMPLETED"


class Report:
    def __init__(self):
        self.errors = []

    def fail(self, path, msg):
        self.errors.append((path, msg))
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print("::error file=%s::SEC-13 %s" % (path, msg))
        else:
            print("FAIL  %s: %s" % (path, msg))


def actions(comp):
    return {a.get(A + "name") for f in comp.findall("intent-filter") for a in f.findall("action")}


def is_launcher(comp):
    for f in comp.findall("intent-filter"):
        acts = {a.get(A + "name") for a in f.findall("action")}
        cats = {c.get(A + "name") for c in f.findall("category")}
        if "android.intent.action.MAIN" in acts and "android.intent.category.LAUNCHER" in cats:
            return True
    return False


def check_manifest(path, rep):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        rep.fail(path, "cannot parse XML (%s)" % exc)
        return
    app = root.find("application")
    if app is None:
        return
    for comp in list(app):
        tag = comp.tag
        if tag not in ("service", "receiver", "provider", "activity", "activity-alias"):
            continue
        name = comp.get(A + "name") or "?"
        exported = comp.get(A + "exported")
        if tag in ("service", "receiver", "provider"):
            if name not in EXPORTED_ALLOWLIST and exported != "false":
                rep.fail(path, '<%s %s> must set android:exported="false" (found %r)' % (tag, name, exported))
        else:
            if comp.find("intent-filter") is not None and exported is None:
                rep.fail(path, "<%s %s> has an intent-filter but no android:exported" % (tag, name))
            if exported == "true" and name not in EXPORTED_ALLOWLIST and not is_launcher(comp):
                rep.fail(path, '<%s %s> is exported="true" but is not a MAIN/LAUNCHER entry point' % (tag, name))
        if LOCKED_BOOT in actions(comp) and comp.get(A + "directBootAware") != "true":
            rep.fail(path, '<%s %s> listens for LOCKED_BOOT_COMPLETED without android:directBootAware="true"' % (tag, name))


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    default_root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap.add_argument("--root", default=default_root, help="repository root (default: parent of tools/)")
    args = ap.parse_args(argv)

    src = os.path.join(args.root, "app", "src")
    main_manifest = os.path.join(src, "main", "AndroidManifest.xml")
    if not os.path.isfile(main_manifest):
        print("error: %s not found (wrong --root?)" % main_manifest, file=sys.stderr)
        return 2

    rep = Report()
    check_manifest(main_manifest, rep)
    for entry in sorted(os.listdir(src)):
        variant = os.path.join(src, entry, "AndroidManifest.xml")
        if entry != "main" and os.path.isfile(variant):
            check_manifest(variant, rep)

    if rep.errors:
        print("\nExported-component guard FAILED with %d problem(s). See SEC-13 in "
              "ForClaude/CLAUDE_BACKEND_PLAN.md before changing component exports." % len(rep.errors))
        return 1
    print("OK  manifest: services/receivers/providers not exported, only the launcher activity is, no LOCKED_BOOT without directBootAware")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

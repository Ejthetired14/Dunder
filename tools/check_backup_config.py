#!/usr/bin/env python3
"""SEC-12: regression guard for the Android backup configuration.

Fails (exit 1) if someone re-enables backup or device transfer of app data.
Dunder keeps Keystore-encrypted secrets in Room and plaintext-capable JSON
backups under filesDir, so cloud backup / device transfer must stay OFF
(see SEC-1 in ForClaude/CLAUDE_BACKEND_PLAN.md).

Checks:
  1. Every AndroidManifest.xml under app/src/*/ that has an <application>:
       - android:allowBackup must not be "true". The MAIN manifest must set it
         to "false" explicitly (the Android default is true).
  2. Main manifest has android:dataExtractionRules -> @xml/<name> that exists.
  3. If android:fullBackupContent is set, it resolves to an existing file.
  4. Neither rules file contains an <include> element (XML comments ignored).
  5. data_extraction_rules.xml has BOTH <cloud-backup> and <device-transfer>,
     each excluding root, file, database, sharedpref and external with path ".".
  6. backup_rules.xml (legacy) root is <full-backup-content>, same excludes.

Usage:  python3 tools/check_backup_config.py [--root REPO_ROOT]
Stdlib only. Exit codes: 0 ok, 1 violations, 2 usage/setup error.
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID_NS
REQUIRED_EXCLUDE_DOMAINS = ("root", "file", "database", "sharedpref", "external")


class Report:
    def __init__(self):
        self.errors = []

    def fail(self, path, msg):
        self.errors.append((path, msg))
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print("::error file=%s::SEC-12 %s" % (path, msg))
        else:
            print("FAIL  %s: %s" % (path, msg))


def parse(path, rep):
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        rep.fail(path, "cannot parse XML (%s)" % exc)
        return None


def resolve_xml_ref(ref, res_xml_dir):
    """'@xml/foo' -> <res_xml_dir>/foo.xml, or None if not an @xml ref."""
    if not ref or not ref.startswith("@xml/"):
        return None
    return os.path.join(res_xml_dir, ref[len("@xml/"):] + ".xml")


def check_no_includes(root, path, rep):
    for el in root.iter("include"):
        rep.fail(path, "contains an <include> element (domain=%r); backup rules must only exclude"
                 % el.get("domain"))


def excluded_domains(container):
    out = set()
    for el in container.findall("exclude"):
        if el.get("path") == ".":
            out.add(el.get("domain"))
    return out


def check_excludes(container, label, path, rep):
    missing = [d for d in REQUIRED_EXCLUDE_DOMAINS if d not in excluded_domains(container)]
    if missing:
        rep.fail(path, "%s does not exclude domain(s) %s with path=\".\"" % (label, ", ".join(missing)))


def check_data_extraction_rules(path, rep):
    root = parse(path, rep)
    if root is None:
        return
    if root.tag != "data-extraction-rules":
        rep.fail(path, "root element is <%s>, expected <data-extraction-rules>" % root.tag)
        return
    check_no_includes(root, path, rep)
    for block in ("cloud-backup", "device-transfer"):
        found = root.findall(block)
        if not found:
            rep.fail(path, "missing <%s> block" % block)
            continue
        for el in found:
            check_excludes(el, "<%s>" % block, path, rep)


def check_legacy_rules(path, rep):
    root = parse(path, rep)
    if root is None:
        return
    if root.tag != "full-backup-content":
        rep.fail(path, "root element is <%s>, expected <full-backup-content>" % root.tag)
        return
    check_no_includes(root, path, rep)
    check_excludes(root, "<full-backup-content>", path, rep)


def check_main_manifest(path, rep):
    root = parse(path, rep)
    if root is None:
        return
    app = root.find("application")
    if app is None:
        rep.fail(path, "main manifest has no <application> element")
        return
    if app.get(A + "allowBackup") != "false":
        rep.fail(path, 'android:allowBackup must be explicitly "false" (found %r)'
                 % app.get(A + "allowBackup"))
    res_xml = os.path.join(os.path.dirname(path), "res", "xml")

    der = app.get(A + "dataExtractionRules")
    if not der:
        rep.fail(path, "android:dataExtractionRules attribute is missing")
    else:
        target = resolve_xml_ref(der, res_xml)
        if target is None or not os.path.isfile(target):
            rep.fail(path, "android:dataExtractionRules=%r does not resolve to a file in res/xml" % der)
        else:
            check_data_extraction_rules(target, rep)

    fbc = app.get(A + "fullBackupContent")
    if fbc:
        target = resolve_xml_ref(fbc, res_xml)
        if target is None or not os.path.isfile(target):
            rep.fail(path, "android:fullBackupContent=%r does not resolve to a file in res/xml" % fbc)
        else:
            check_legacy_rules(target, rep)


def check_variant_manifest(path, rep):
    root = parse(path, rep)
    if root is None:
        return
    app = root.find("application")
    if app is not None and app.get(A + "allowBackup") == "true":
        rep.fail(path, 'variant manifest sets android:allowBackup="true"')


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
    check_main_manifest(main_manifest, rep)
    for entry in sorted(os.listdir(src)):
        if entry == "main":
            continue
        variant = os.path.join(src, entry, "AndroidManifest.xml")
        if os.path.isfile(variant):
            check_variant_manifest(variant, rep)

    if rep.errors:
        print("\nBackup-config guard FAILED with %d problem(s). See SEC-1 / SEC-12 in "
              "ForClaude/CLAUDE_BACKEND_PLAN.md before changing backup behaviour." % len(rep.errors))
        return 1
    print("OK  backup config: allowBackup=false, no <include>, cloud-backup and device-transfer exclude all domains")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

#!/usr/bin/env python3
"""Tests for tools/check_backup_config.py (SEC-12). Run: python3 tools/test_check_backup_config.py"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
GUARD = os.path.join(HERE, "check_backup_config.py")

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".AgoriaApplication"
        android:allowBackup="%(allow)s"
        %(der)s
        %(fbc)s
        android:label="@string/app_name">
    </application>
</manifest>
"""

DOMAINS = ("root", "file", "database", "sharedpref", "external")


def excludes(domains=DOMAINS):
    return "\n".join('<exclude domain="%s" path="." />' % d for d in domains)


def extraction(cloud=None, device=None, extra=""):
    cloud = excludes() if cloud is None else cloud
    device = excludes() if device is None else device
    blocks = ""
    if cloud is not False:
        blocks += "<cloud-backup>%s%s</cloud-backup>" % (cloud, extra)
    if device is not False:
        blocks += "<device-transfer>%s</device-transfer>" % device
    return "<data-extraction-rules>%s</data-extraction-rules>" % blocks


def legacy(body=None):
    return "<full-backup-content>%s</full-backup-content>" % (excludes() if body is None else body)


class GuardTest(unittest.TestCase):
    def build(self, allow="false", der='android:dataExtractionRules="@xml/data_extraction_rules"',
              fbc='android:fullBackupContent="@xml/backup_rules"', rules=None, legacy_rules=None,
              variants=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        main = os.path.join(tmp.name, "app", "src", "main")
        os.makedirs(os.path.join(main, "res", "xml"))
        with open(os.path.join(main, "AndroidManifest.xml"), "w") as f:
            f.write(MANIFEST % {"allow": allow, "der": der, "fbc": fbc})
        if rules is not False:
            with open(os.path.join(main, "res", "xml", "data_extraction_rules.xml"), "w") as f:
                f.write(extraction() if rules is None else rules)
        if legacy_rules is not False:
            with open(os.path.join(main, "res", "xml", "backup_rules.xml"), "w") as f:
                f.write(legacy() if legacy_rules is None else legacy_rules)
        for name, content in (variants or {}).items():
            d = os.path.join(tmp.name, "app", "src", name)
            os.makedirs(d)
            with open(os.path.join(d, "AndroidManifest.xml"), "w") as f:
                f.write(content)
        return tmp.name

    def run_guard(self, root):
        env = dict(os.environ)
        env.pop("GITHUB_ACTIONS", None)
        p = subprocess.run([sys.executable, GUARD, "--root", root], capture_output=True, text=True, env=env)
        return p.returncode, p.stdout + p.stderr

    def assertFails(self, root, needle):
        code, out = self.run_guard(root)
        self.assertEqual(code, 1, out)
        self.assertIn(needle, out)

    # --- passing configurations ---
    def test_good_config_passes(self):
        code, out = self.run_guard(self.build())
        self.assertEqual(code, 0, out)

    def test_commented_out_include_is_ignored(self):
        rules = extraction().replace(
            "<cloud-backup>", '<cloud-backup><!-- <include domain="database" path="." /> -->')
        code, out = self.run_guard(self.build(rules=rules))
        self.assertEqual(code, 0, out)

    def test_no_fullbackupcontent_is_ok(self):
        code, out = self.run_guard(self.build(fbc="", legacy_rules=False))
        self.assertEqual(code, 0, out)

    # --- manifest regressions ---
    def test_allow_backup_true_fails(self):
        self.assertFails(self.build(allow="true"), "allowBackup must be explicitly")

    def test_allow_backup_missing_fails(self):
        root = self.build()
        p = os.path.join(root, "app", "src", "main", "AndroidManifest.xml")
        with open(p) as f:
            text = f.read().replace('android:allowBackup="false"', "")
        with open(p, "w") as f:
            f.write(text)
        self.assertFails(root, "allowBackup must be explicitly")

    def test_missing_data_extraction_attr_fails(self):
        self.assertFails(self.build(der=""), "dataExtractionRules attribute is missing")

    def test_data_extraction_points_to_missing_file_fails(self):
        self.assertFails(self.build(rules=False), "does not resolve to a file")

    def test_variant_manifest_allow_backup_true_fails(self):
        v = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
             '<application android:allowBackup="true"/></manifest>')
        self.assertFails(self.build(variants={"debug": v}), "variant manifest")

    def test_variant_manifest_without_flag_is_ok(self):
        v = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>'
        code, out = self.run_guard(self.build(variants={"debug": v}))
        self.assertEqual(code, 0, out)

    # --- rules-file regressions ---
    def test_include_in_cloud_backup_fails(self):
        rules = extraction(extra='<include domain="database" path="." />')
        self.assertFails(self.build(rules=rules), "<include>")

    def test_include_in_legacy_rules_fails(self):
        self.assertFails(self.build(legacy_rules=legacy(excludes() + '<include domain="file" path="." />')),
                         "<include>")

    def test_missing_device_transfer_fails(self):
        self.assertFails(self.build(rules=extraction(device=False)), "missing <device-transfer>")

    def test_missing_cloud_backup_fails(self):
        self.assertFails(self.build(rules=extraction(cloud=False)), "missing <cloud-backup>")

    def test_missing_database_exclude_fails(self):
        d = tuple(x for x in DOMAINS if x != "database")
        self.assertFails(self.build(rules=extraction(cloud=excludes(d))), "database")

    def test_exclude_with_narrow_path_does_not_count(self):
        narrow = excludes(("root", "file", "sharedpref", "external")) + '<exclude domain="database" path="x.db" />'
        self.assertFails(self.build(legacy_rules=legacy(narrow)), "database")

    def test_malformed_xml_fails(self):
        self.assertFails(self.build(rules="<data-extraction-rules><cloud-backup>"), "cannot parse XML")

    def test_wrong_root_element_fails(self):
        self.assertFails(self.build(rules="<full-backup-content/>"), "expected <data-extraction-rules>")

    # --- setup errors ---
    def test_wrong_root_dir_exits_2(self):
        with tempfile.TemporaryDirectory() as d:
            code, out = self.run_guard(d)
            self.assertEqual(code, 2, out)


if __name__ == "__main__":
    unittest.main(verbosity=2)

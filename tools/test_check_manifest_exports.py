#!/usr/bin/env python3
"""Tests for tools/check_manifest_exports.py (SEC-13). Run: python3 tools/test_check_manifest_exports.py"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
GUARD = os.path.join(HERE, "check_manifest_exports.py")

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
LAUNCHER = ('<intent-filter><action android:name="android.intent.action.MAIN"/>'
            '<category android:name="android.intent.category.LAUNCHER"/></intent-filter>')
BOOT = '<intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>'
LOCKED = '<intent-filter><action android:name="android.intent.action.LOCKED_BOOT_COMPLETED"/></intent-filter>'


def manifest(body):
    return '<manifest %s><application>%s</application></manifest>' % (NS, body)


GOOD_BODY = (
    '<activity android:name=".MainActivity" android:exported="true">%s</activity>'
    '<activity android:name=".viewer.BuildViewerActivity" android:exported="false"/>'
    '<service android:name=".service.AutonomyService" android:exported="false"/>'
    '<receiver android:name=".service.BootReceiver" android:exported="false">%s</receiver>'
    '<provider android:name="androidx.core.content.FileProvider" android:exported="false"/>'
) % (LAUNCHER, BOOT)


class GuardTest(unittest.TestCase):
    def build(self, body=GOOD_BODY, variants=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        main = os.path.join(tmp.name, "app", "src", "main")
        os.makedirs(main)
        with open(os.path.join(main, "AndroidManifest.xml"), "w") as f:
            f.write(manifest(body))
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

    def test_good_config_passes(self):
        code, out = self.run_guard(self.build())
        self.assertEqual(code, 0, out)

    def test_exported_boot_receiver_fails(self):
        body = GOOD_BODY.replace('BootReceiver" android:exported="false"', 'BootReceiver" android:exported="true"')
        self.assertFails(self.build(body), 'BootReceiver> must set android:exported="false"')

    def test_receiver_without_exported_fails(self):
        body = GOOD_BODY.replace(' android:exported="false">' + BOOT, ">" + BOOT)
        self.assertFails(self.build(body), "BootReceiver")

    def test_exported_service_fails(self):
        body = GOOD_BODY.replace('AutonomyService" android:exported="false"', 'AutonomyService" android:exported="true"')
        self.assertFails(self.build(body), "AutonomyService")

    def test_exported_provider_fails(self):
        body = GOOD_BODY.replace('FileProvider" android:exported="false"', 'FileProvider" android:exported="true"')
        self.assertFails(self.build(body), "FileProvider")

    def test_non_launcher_exported_activity_fails(self):
        body = GOOD_BODY + '<activity android:name=".Other" android:exported="true"/>'
        self.assertFails(self.build(body), "not a MAIN/LAUNCHER")

    def test_activity_with_filter_and_no_exported_fails(self):
        body = GOOD_BODY + '<activity android:name=".Deep">%s</activity>' % BOOT
        self.assertFails(self.build(body), "no android:exported")

    def test_locked_boot_without_direct_boot_aware_fails(self):
        self.assertFails(self.build(GOOD_BODY.replace(BOOT, BOOT + LOCKED)), "LOCKED_BOOT_COMPLETED")

    def test_locked_boot_with_direct_boot_aware_passes(self):
        body = GOOD_BODY.replace(BOOT, BOOT + LOCKED).replace(
            'BootReceiver" android:exported="false"',
            'BootReceiver" android:exported="false" android:directBootAware="true"')
        code, out = self.run_guard(self.build(body))
        self.assertEqual(code, 0, out)

    def test_variant_manifest_exported_service_fails(self):
        v = manifest('<service android:name=".Dbg" android:exported="true"/>')
        self.assertFails(self.build(variants={"debug": v}), "Dbg")

    def test_malformed_xml_fails(self):
        root = self.build()
        with open(os.path.join(root, "app", "src", "main", "AndroidManifest.xml"), "w") as f:
            f.write("<manifest><application>")
        self.assertFails(root, "cannot parse XML")

    def test_wrong_root_dir_exits_2(self):
        with tempfile.TemporaryDirectory() as d:
            code, out = self.run_guard(d)
            self.assertEqual(code, 2, out)


if __name__ == "__main__":
    unittest.main(verbosity=2)

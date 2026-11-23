#!/usr/bin/env python3
"""SEC-3 regression guard: GitHub Actions workflow hygiene.

Stdlib only, textual checks (no YAML library needed on the runner).
Fails when a workflow under .github/workflows:
  1. is one of the removed files (apply-phase2.yml, generate-keystore.yml);
  2. fetches remote patches/scripts from paste sites or pipes curl/wget into a shell;
  3. generates a keystore (keytool -genkey/-genkeypair) -> release must use real secrets;
  4. uploads a keystore (*.jks, *.keystore, *.p12) as an artifact;
  5. lacks a top-level `permissions:` block, uses write-all, or grants write at top level;
  6. interpolates `${{ secrets.* }}` directly into a script line (use `env:` instead);
  7. attaches a debug APK to a release (`gh release create ... debug ...`).

Exit codes: 0 ok, 1 violations, 2 wrong --root.
"""
import argparse
import os
import re
import sys

REMOVED_FILES = {"apply-phase2.yml", "generate-keystore.yml"}
PASTE_HOSTS = re.compile(r"paste\.rs|pastebin\.com|hastebin|ix\.io|transfer\.sh|termbin\.com", re.I)
PIPE_TO_SHELL = re.compile(r"\b(curl|wget)\b[^\n|]*\|\s*(sudo\s+)?(ba)?sh\b")
KEYTOOL_GEN = re.compile(r"\bkeytool\b[^\n]*-genkey(pair)?\b")
KEYSTORE_PATH = re.compile(r"\.(jks|keystore|p12|pfx)\b", re.I)
SECRET_EXPR = re.compile(r"\$\{\{\s*secrets\.")
# mapping lines such as `  NAME: ${{ secrets.X }}` or `  token: ${{ secrets.X }}` are fine
MAPPING_LINE = re.compile(r"^\s*-?\s*[A-Za-z_][\w-]*:\s*\$\{\{\s*secrets\.[\w]+\s*\}\}\s*$")


def logical_lines(text):
    """Join shell lines that end with a backslash."""
    out, buf = [], ""
    for raw in text.splitlines():
        if raw.rstrip().endswith("\\"):
            buf += raw.rstrip()[:-1] + " "
        else:
            out.append(buf + raw)
            buf = ""
    if buf:
        out.append(buf)
    return out


def top_level_permissions(text):
    """Return (found, list_of_lines_in_block, inline_value)."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^permissions:\s*(.*)$", line)
        if m:
            inline = m.group(1).split("#")[0].strip()
            block = []
            for nxt in lines[i + 1:]:
                if nxt.strip() == "" or nxt.lstrip().startswith("#"):
                    continue
                if nxt.startswith((" ", "\t")):
                    block.append(nxt.split("#")[0].strip())
                else:
                    break
            return True, block, inline
    return False, [], ""


def check_file(path, rel, errors):
    name = os.path.basename(path)
    if name in REMOVED_FILES:
        errors.append((rel, f"{name} must not exist (SEC-3: remote-patch / keystore-printing workflow)."))
        return
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    code_lines = [l for l in text.splitlines() if not l.lstrip().startswith("#")]
    code = "\n".join(code_lines)

    if PASTE_HOSTS.search(code):
        errors.append((rel, "references a public paste/transfer host; never fetch patches or scripts from mutable public URLs."))
    if PIPE_TO_SHELL.search(code):
        errors.append((rel, "pipes curl/wget output into a shell."))
    if KEYTOOL_GEN.search(code):
        errors.append((rel, "generates a keystore in CI; create the release key locally once and store it as secrets."))
    for l in code_lines:
        if "upload-artifact" in l:
            break
    if re.search(r"upload-artifact", code):
        # look at the `path:` block after each upload-artifact step
        for m in re.finditer(r"upload-artifact[^\n]*\n(?:.*\n){0,12}?\s*path:\s*(\|?)\s*\n?((?:[ \t]+[^\n]*\n?)*)", code):
            if KEYSTORE_PATH.search(m.group(2)):
                errors.append((rel, "uploads a keystore file as an artifact."))
        if re.search(r"upload-artifact[^\n]*\n(?:.*\n){0,12}?\s*path:[^\n]*\.(jks|keystore|p12|pfx)\b", code, re.I):
            errors.append((rel, "uploads a keystore file as an artifact."))

    found, block, inline = top_level_permissions(text)
    if not found:
        errors.append((rel, "no top-level `permissions:` block (default token scope is too broad)."))
    else:
        if inline in ("write-all",) or any(b.replace(" ", "") == "write-all" for b in block):
            errors.append((rel, "top-level permissions is write-all."))
        for b in block:
            if re.match(r"^[\w-]+:\s*write\s*$", b):
                errors.append((rel, f"top-level permission `{b}`; grant write per job instead."))

    for n, l in enumerate(code_lines, 1):
        if SECRET_EXPR.search(l) and not MAPPING_LINE.match(l) and not l.lstrip().startswith("if:"):
            errors.append((rel, f"line {n}: secret interpolated into a script line; pass it via `env:`."))

    for l in logical_lines("\n".join(code_lines)):
        if re.search(r"\bgh\s+release\s+create\b", l) and re.search(r"debug", l, re.I):
            errors.append((rel, "attaches a debug APK to a release."))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    args = ap.parse_args(argv)
    wf = os.path.join(args.root, ".github", "workflows")
    if not os.path.isdir(wf):
        print(f"error: {wf} not found; wrong --root?", file=sys.stderr)
        return 2
    errors = []
    for name in sorted(os.listdir(wf)):
        if name.endswith((".yml", ".yaml")):
            check_file(os.path.join(wf, name), f".github/workflows/{name}", errors)
    in_actions = os.environ.get("GITHUB_ACTIONS") == "true"
    for rel, msg in errors:
        print(f"::error file={rel}::{msg}" if in_actions else f"FAIL {rel}: {msg}")
    if errors:
        return 1
    print("workflow hygiene OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

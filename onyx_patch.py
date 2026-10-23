#!/usr/bin/env python3
"""
onyx_patch.py — add the BOOX ("Onyx") fast ink bridge to an Obsidian APK.

What it does
------------
1. Decodes the APK with apktool.
2. Adds an empty ContentProvider to the manifest. The provider runs at process start and attaches
   `window.onyx` to the Capacitor activity's WebView — no smali editing, no Capacitor version
   dependency.
3. Adds `assets/onyx/shim.js` (the friendly JS API).
4. Compiles a tiny dex (the reflection client for SurfaceFlinger's BOOX pen transactions, plus the
   JS bridge) with the JDK and d8, and adds it as `classesN.dex`.
5. Rebuilds, aligns and signs the APK.

The whole injector is written against standard Android APIs used through reflection, so nothing from
the BOOX SDK is bundled and the same patch works on any BOOX device whose firmware speaks the
transaction codes (verified codes are from firmware 4.3; see docs/09-ink.md in BooxUltimatum).

Requirements: Python 3.8+, a JDK (17 recommended). apktool and R8 are downloaded automatically on
first use (or point at local copies with --apktool/--r8-jar). Signing uses apksigner+zipalign when
present, otherwise uber-apk-signer is downloaded.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INJECTOR_DIR = ROOT / "injector"
SRC_DIR = INJECTOR_DIR / "src"
STUB_DIR = INJECTOR_DIR / "stubs"
ASSET_DIR = INJECTOR_DIR / "assets"

PROVIDER_CLASS = "app.onyx.obsidian.OnyxInitProvider"
PROVIDER_AUTHORITY = "app.onyx.obsidian.init"
SHIM_REL = "onyx/shim.js"
MARKER_REL = "onyx/version.txt"

APKTOOL_VERSION = "2.9.3"
APKTOOL_URL = (
    "https://github.com/iBotPeaches/Apktool/releases/download/v{0}/apktool_{0}.jar".format(
        APKTOOL_VERSION
    )
)
R8_METADATA_URL = "https://maven.google.com/com/android/tools/r8/maven-metadata.xml"
R8_JAR_URL = "https://maven.google.com/com/android/tools/r8/{0}/r8-{0}.jar"
UBER_VERSION = "1.3.0"
UBER_URL = (
    "https://github.com/patrickfav/uber-apk-signer/releases/download/v{0}/uber-apk-signer-{0}.jar".format(
        UBER_VERSION
    )
)

CACHE = Path(os.environ.get("ONYX_PATCH_CACHE", str(Path.home() / ".cache" / "onyx-patch")))


# --------------------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------------------

class ToolError(RuntimeError):
    pass


def info(msg: str) -> None:
    print("[onyx-patch] " + msg, flush=True)


def warn(msg: str) -> None:
    print("[onyx-patch] WARNING: " + msg, file=sys.stderr, flush=True)


def die(msg: str, code: int = 1):
    print("[onyx-patch] ERROR: " + msg, file=sys.stderr, flush=True)
    raise SystemExit(code)


def run(cmd, cwd=None, capture=False, verbose=False):
    if verbose or not capture:
        printable = " ".join(str(c) for c in cmd)
        print("+ " + printable, flush=True)
    if capture:
        return subprocess.run(
            [str(c) for c in cmd],
            cwd=str(cwd) if cwd else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
    return subprocess.run([str(c) for c in cmd], cwd=str(cwd) if cwd else None)


def which(*names):
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    for d in (
        "/run/current-system/sw/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/opt/homebrew/bin",
    ):
        for n in names:
            p = Path(d) / n
            if p.exists():
                return str(p)
    return None


def likely_java_bins():
    """Look for a JDK in $JAVA_HOME or common Nix profiles when java is not on PATH."""
    candidates = []
    jh = os.environ.get("JAVA_HOME")
    if jh:
        candidates.append(Path(jh) / "bin")
    for base in ("/etc/profiles/per-user", str(Path.home() / ".nix-profile"), "/run/current-system"):
        pass
    return candidates


def download(url: str, dest: Path, force=False) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0 and not force:
        return dest
    info("downloading " + url)
    req = urllib.request.Request(url, headers={"User-Agent": "onyx-patch/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    if dest.stat().st_size == 0:
        die("download failed (empty file): " + url)
    return dest


# --------------------------------------------------------------------------------------
# toolchain
# --------------------------------------------------------------------------------------

class Toolchain:
    def __init__(self):
        self.java = None
        self.javac = None
        self.jar = None
        self.keytool = None
        self.java_home = None
        self.apktool = None          # path to jar or executable
        self.apktool_is_jar = False
        self.r8_jar = None
        self.zipalign = None
        self.apksigner = None
        self.uber_jar = None


def resolve_toolchain(args) -> Toolchain:
    tc = Toolchain()

    injector_dex = getattr(args, "injector_dex", None)
    tc.java = args.java or which("java") or _home_bin("java")
    tc.javac = which("javac") or _home_bin("javac")
    tc.jar = which("jar") or _home_bin("jar")
    tc.keytool = which("keytool") or _home_bin("keytool")
    if not tc.java:
        die("java not found. Install a JDK (17+) and make sure it is on PATH or set JAVA_HOME.")
    tc.java_home = _java_home(tc.java)
    if not tc.javac and not injector_dex:
        die("javac not found. Install a JDK (not just a JRE), or pass --injector-dex with a prebuilt dex.")
    if not tc.jar and not injector_dex:
        die("jar not found. Install a JDK (not just a JRE).")

    # apktool
    if args.apktool:
        tc.apktool = args.apktool
    else:
        found = which("apktool")
        if found:
            tc.apktool = found
    if tc.apktool:
        tc.apktool_is_jar = str(tc.apktool).lower().endswith(".jar")
    else:
        if args.offline:
            die("apktool not found and --offline was given. Install apktool or pass --apktool.")
        tc.apktool = str(download(APKTOOL_URL, CACHE / ("apktool_%s.jar" % APKTOOL_VERSION)))
        tc.apktool_is_jar = True

    # r8 (only needed when compiling the injector)
    if not injector_dex:
        if args.r8_jar:
            tc.r8_jar = args.r8_jar
        elif args.offline:
            die("r8 not given and --offline was set. Pass --r8-jar or --injector-dex.")
        else:
            tc.r8_jar = str(_ensure_r8())

    # signing
    tc.zipalign = which("zipalign")
    tc.apksigner = which("apksigner")
    if not (tc.zipalign and tc.apksigner):
        if args.uber_jar:
            tc.uber_jar = args.uber_jar
        elif not args.offline and not getattr(args, "no_sign", False):
            try:
                tc.uber_jar = str(download(UBER_URL, CACHE / ("uber-apk-signer-%s.jar" % UBER_VERSION)))
            except Exception as e:  # noqa: BLE001
                warn("could not download uber-apk-signer (%s)" % e)
    return tc


def _home_bin(name):
    jh = os.environ.get("JAVA_HOME")
    if jh:
        p = Path(jh) / "bin" / name
        if p.exists():
            return str(p)
    return None


def _java_home(java):
    try:
        out = run([java, "-XshowSettings:properties", "-version"], capture=True).stdout
        m = re.search(r"java\.home\s*=\s*(\S+)", out)
        if m:
            return m.group(1)
    except Exception:  # noqa: BLE001
        pass
    return None


def _ensure_r8() -> Path:
    try:
        import re as _re
        xml = urllib.request.urlopen(
            urllib.request.Request(R8_METADATA_URL, headers={"User-Agent": "onyx-patch/1.0"}),
            timeout=60,
        ).read().decode("utf-8", "replace")
        versions = _re.findall(r"<version>([^<]+)</version>", xml)
        stable = [v for v in versions if not _re.search(r"alpha|beta|rc|dev", v, _re.I)]
        pick = stable[-1] if stable else versions[-1]
    except Exception as e:  # noqa: BLE001
        raise ToolError("could not read r8 metadata: %s" % e)
    return download(R8_JAR_URL.format(pick), CACHE / ("r8-%s.jar" % pick))


def apktool_cmd(tc: Toolchain):
    if tc.apktool_is_jar:
        return [tc.java, "-jar", tc.apktool]
    return [tc.apktool]


# --------------------------------------------------------------------------------------
# injector build
# --------------------------------------------------------------------------------------

def build_injector(tc: Toolchain, min_api: int, verbose: bool) -> Path:
    if not SRC_DIR.exists():
        die("injector sources not found at %s (did you keep the injector/ folder?)" % SRC_DIR)
    out = Path(tempfile.mkdtemp(prefix="onyx-injector-", dir=str(CACHE)))
    stubs_classes = out / "stubclasses"
    stubs_classes.mkdir(parents=True, exist_ok=True)
    src_classes = out / "classes"
    src_classes.mkdir(parents=True, exist_ok=True)

    stubs = sorted(str(p) for p in STUB_DIR.rglob("*.java"))
    src = sorted(str(p) for p in SRC_DIR.rglob("*.java"))
    if not src:
        die("no injector java sources found")

    info("compiling Android API stubs")
    _javac(tc, stubs, stubs_classes, [], verbose)

    stubs_jar = out / "onyx-stubs.jar"
    _jar_create(tc, stubs_jar, stubs_classes, verbose)

    info("compiling injector")
    _javac(tc, src, src_classes, [str(stubs_jar)], verbose)

    libs = [str(stubs_jar)]
    jlib = _ensure_java_lib(tc, verbose)
    if jlib:
        libs.append(jlib)

    info("converting to dex (min-api %d)" % min_api)
    classes = sorted(str(p) for p in src_classes.rglob("*.class"))
    dex_dir = out / "dex"
    dex_dir.mkdir(parents=True, exist_ok=True)

    base = [tc.java, "-cp", tc.r8_jar, "com.android.tools.r8.D8", "--min-api", str(min_api), "--output", str(dex_dir)]
    variants = [
        base + _lib_args(libs) + classes,
        base + _lib_args(libs) + ["--no-desugaring"] + classes,
        base + ["--no-desugaring"] + classes,
        base + classes,
    ]
    last = None
    for cmd in variants:
        r = run(cmd, capture=True, verbose=verbose)
        if r.returncode == 0 and (dex_dir / "classes.dex").exists():
            return dex_dir / "classes.dex"
        last = (r.stdout or "")[-4000:]
    die("d8 failed to produce a dex:\n" + (last or "unknown error"))


def _lib_args(libs):
    out = []
    for l in libs:
        out += ["--lib", l]
    return out


def _javac(tc, sources, out_dir, classpath, verbose):
    base = [tc.javac, "-d", str(out_dir), "-Xlint:-options", "-encoding", "UTF-8"]
    if classpath:
        base += ["-cp", os.pathsep.join(classpath)]
    variants = [
        base + ["--release", "8"],
        base + ["--release", "11"],
        base + ["-source", "8", "-target", "8"],
        list(base),
    ]
    last = None
    for cmd in variants:
        r = run(cmd + sources, capture=True, verbose=verbose)
        if r.returncode == 0:
            return
        last = (r.stdout or "")[-4000:]
    die("javac failed:\n" + (last or "unknown error"))


def _jar_create(tc, jar_path, classes_dir, verbose):
    cmd = ([tc.jar, "cf", str(jar_path), "-C", str(classes_dir), "."] if tc.jar
           else [tc.java, "-jar", str(_ensure_r8()), "help"])
    r = run(cmd, capture=True, verbose=verbose)
    if r.returncode != 0:
        die("jar creation failed:\n" + (r.stdout or ""))


def _ensure_java_lib(tc, verbose):
    """A library jar with the JDK's java.base classes (helps d8 resolve java.* properly)."""
    if not tc.java_home:
        return None
    jmod = Path(tc.java_home) / "jmods" / "java.base.jmod"
    if not jmod.exists():
        return None
    key = "javalib-%s.jar" % Path(tc.java_home).name
    dest = CACHE / key
    if dest.exists() and dest.stat().st_size > 0:
        return str(dest)
    jmod_tool = _home_bin("jmod") or which("jmod")
    if not jmod_tool:
        return None
    try:
        extract = Path(tempfile.mkdtemp(prefix="onyx-jmod-", dir=str(CACHE)))
        r = run([jmod_tool, "extract", "--dir", str(extract), str(jmod)], capture=True, verbose=verbose)
        if r.returncode != 0:
            return None
        classes_dir = extract / "classes"
        if not classes_dir.exists():
            return None
        _jar_create(tc, dest, classes_dir, verbose)
        return str(dest)
    except Exception:  # noqa: BLE001
        return None


# --------------------------------------------------------------------------------------
# apk manipulation
# --------------------------------------------------------------------------------------

def decode_apk(tc, apk: Path, work: Path, verbose: bool):
    info("decoding APK with apktool")
    cmd = apktool_cmd(tc) + ["d", "-f", "-s", "-o", str(work), str(apk)]
    r = run(cmd, capture=True, verbose=verbose)
    if r.returncode != 0 or not (work / "AndroidManifest.xml").exists():
        # retry with full smali decode (some APKs need it)
        warn("decode with -s failed, retrying with full decode")
        shutil.rmtree(work, ignore_errors=True)
        cmd = apktool_cmd(tc) + ["d", "-f", "-o", str(work), str(apk)]
        r = run(cmd, capture=True, verbose=verbose)
        if r.returncode != 0:
            die("apktool decode failed:\n" + (r.stdout or "")[-4000:])


MANIFEST_BLOCK = """\
        <provider
            android:name="{provider}"
            android:authorities="{authority}"
            android:exported="false"
            android:enabled="true" />
"""


def patch_manifest(work: Path, force: bool):
    manifest = work / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    if PROVIDER_AUTHORITY in text:
        if force:
            return
        die("this APK already contains the onyx provider. Pass --force to patch anyway.")
    block = "\n" + MANIFEST_BLOCK.format(provider=PROVIDER_CLASS, authority=PROVIDER_AUTHORITY)
    if "</application>" not in text:
        die("could not find </application> in AndroidManifest.xml")
    text = text.replace("</application>", block + "    </application>", 1)
    manifest.write_text(text, encoding="utf-8")
    info("manifest patched (provider added)")


def add_assets(work: Path):
    dest = work / "assets" / SHIM_REL
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ASSET_DIR / SHIM_REL, dest)
    (dest.parent / "version.txt").write_text("onyx-patch 1.0.0\n", encoding="utf-8")
    info("assets added (%s)" % SHIM_REL)


def min_sdk_of(work: Path) -> int:
    yml = work / "apktool.yml"
    if yml.exists():
        m = re.search(r"minSdkVersion:\s*'?(\d+)", yml.read_text(encoding="utf-8"))
        if m:
            return int(m.group(1))
    try:
        tree = ET.parse(str(work / "AndroidManifest.xml"))
        for el in tree.iter():
            for k, v in el.attrib.items():
                if k.endswith("minSdkVersion"):
                    return int(v)
    except Exception:  # noqa: BLE001
        pass
    return 21


def rebuild_apk(tc, work: Path, out_unsigned: Path, verbose: bool):
    info("rebuilding APK")
    cmd = apktool_cmd(tc) + ["b", str(work), "-o", str(out_unsigned)]
    r = run(cmd, capture=True, verbose=verbose)
    if r.returncode != 0 or not out_unsigned.exists():
        die("apktool build failed:\n" + (r.stdout or "")[-4000:])


def add_injector_dex(src_apk: Path, dex: Path, out_apk: Path, verbose: bool):
    """Copy src_apk to out_apk, appending the injector as classesN.dex (preserving compression)."""
    with zipfile.ZipFile(src_apk) as zin:
        names = zin.namelist()
        max_index = 1
        for n in names:
            m = re.fullmatch(r"classes(\d*)\.dex", n)
            if m:
                idx = int(m.group(1)) if m.group(1) else 1
                max_index = max(max_index, idx)
        new_name = "classes%d.dex" % (max_index + 1)
        info("adding %s" % new_name)
        with zipfile.ZipFile(out_apk, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                # keep resources.arsc uncompressed and let zipalign handle alignment
                if item.filename == "resources.arsc":
                    zout.writestr(item, data, compress_type=zipfile.ZIP_STORED)
                else:
                    zout.writestr(item, data, compress_type=item.compress_type)
            zout.write(dex, new_name)


def ensure_keystore(tc: Toolchain) -> Path:
    ks = CACHE / "debug.keystore"
    if ks.exists():
        return ks
    if not tc.keytool:
        die("keytool not found and no keystore provided (pass --keystore).")
    info("generating debug keystore")
    r = run(
        [
            tc.keytool, "-genkeypair", "-keystore", str(ks),
            "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Onyx Patch, OU=Dev, O=OnyxPatch, C=US",
        ],
        capture=True,
    )
    if r.returncode != 0 or not ks.exists():
        die("keytool failed:\n" + (r.stdout or ""))
    return ks


def sign_apk(tc: Toolchain, in_apk: Path, out_apk: Path, keystore: Path, verbose: bool):
    if tc.zipalign and tc.apksigner:
        aligned = out_apk.with_name(out_apk.stem + "-aligned.apk")
        info("zipaligning")
        run([tc.zipalign, "-p", "-f", "4", str(in_apk), str(aligned)], capture=not verbose)
        info("signing with apksigner")
        r = run(
            [
                tc.apksigner, "sign",
                "--ks", str(keystore),
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", str(out_apk), str(aligned),
            ],
            capture=not verbose,
        )
        aligned.unlink(missing_ok=True)
        if r.returncode != 0:
            die("apksigner failed:\n" + (r.stdout or ""))
        return
    if tc.uber_jar:
        info("signing with uber-apk-signer (aligns too)")
        outdir = Path(tempfile.mkdtemp(prefix="onyx-sign-", dir=str(CACHE)))
        r = run(
            [
                tc.java, "-jar", tc.uber_jar,
                "-a", str(in_apk),
                "--ks", str(keystore),
                "--ksAlias", "androiddebugkey",
                "--ksPass", "android",
                "--ksKeyPass", "android",
                "--allowResign",
                "-o", str(outdir),
            ],
            capture=not verbose,
        )
        produced = list(outdir.glob("*-signed.apk")) + list(outdir.glob("*.apk"))
        if r.returncode != 0 or not produced:
            die("uber-apk-signer failed:\n" + (r.stdout or ""))
        shutil.copyfile(produced[0], out_apk)
        return
    die("no signer available: install apksigner+zipalign, or pass --uber-jar, or use --no-sign.")


# --------------------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------------------

def cmd_doctor(args) -> int:
    tc = resolve_toolchain(args)
    print("java       :", tc.java, "(%s)" % (tc.java_home or "?"))
    print("javac      :", tc.javac)
    print("apktool    :", tc.apktool, "(jar)" if tc.apktool_is_jar else "(exec)")
    print("r8         :", tc.r8_jar)
    print("zipalign   :", tc.zipalign)
    print("apksigner  :", tc.apksigner)
    print("uber signer:", tc.uber_jar)
    return 0


def cmd_patch(args) -> int:
    inp = Path(args.input).resolve()
    if not inp.exists():
        die("input APK not found: %s" % inp)
    tc = resolve_toolchain(args)

    out = Path(args.output).resolve() if args.output else inp.with_name(inp.stem + "-onyx.apk")
    if out == inp:
        die("refusing to overwrite the input APK; choose a different --output")

    CACHE.mkdir(parents=True, exist_ok=True)

    work = Path(args.work_dir).resolve() if args.work_dir else Path(
        tempfile.mkdtemp(prefix="onyx-work-", dir=str(CACHE))
    )
    try:
        decode_apk(tc, inp, work, args.verbose)
        min_api = min_sdk_of(work)
        if min_api < 21:
            min_api = 21
        info("minSdkVersion = %d" % min_api)

        patch_manifest(work, args.force)
        add_assets(work)

        if args.injector_dex:
            dex = Path(args.injector_dex).resolve()
            if not dex.exists():
                die("injector dex not found: %s" % dex)
        else:
            dex = build_injector(tc, min_api, args.verbose)

        unsigned = work / "onyx-unsigned.apk"
        rebuild_apk(tc, work, unsigned, args.verbose)

        merged = work / "onyx-merged.apk"
        add_injector_dex(unsigned, dex, merged, args.verbose)

        out.parent.mkdir(parents=True, exist_ok=True)
        if args.no_sign:
            shutil.copyfile(merged, out)
            info("wrote UNSIGNED apk: %s" % out)
        else:
            keystore = Path(args.keystore).resolve() if args.keystore else ensure_keystore(tc)
            sign_apk(tc, merged, out, keystore, args.verbose)
            info("wrote SIGNED apk: %s" % out)

        info("done. Install with: adb install -r \"%s\"" % out)
        if not args.no_sign:
            warn(
                "the APK is signed with a different key than the official build, so uninstall the "
                "official Obsidian first (your vaults live in shared storage and are not affected; "
                "app settings may be)."
            )
        return 0
    finally:
        if not args.keep_work and work.exists():
            shutil.rmtree(work, ignore_errors=True)
        elif args.keep_work:
            info("kept work dir: %s" % work)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="onyx_patch.py",
        description="Patch an Obsidian APK with the BOOX fast-ink bridge (window.onyx).",
    )
    sub = p.add_subparsers(dest="command", required=True)

    def common(sp):
        sp.add_argument("--java", help="path to java")
        sp.add_argument("--apktool", help="path to apktool jar or executable")
        sp.add_argument("--r8-jar", help="path to r8.jar (used as d8)")
        sp.add_argument("--uber-jar", help="path to uber-apk-signer.jar")
        sp.add_argument("--offline", action="store_true", help="never download anything")
        sp.add_argument("--verbose", action="store_true")

    d = sub.add_parser("doctor", help="show the detected toolchain")
    common(d)

    pa = sub.add_parser("patch", help="patch an APK")
    common(pa)
    pa.add_argument("input", help="input Obsidian APK")
    pa.add_argument("-o", "--output", help="output APK (default: <input>-onyx.apk)")
    pa.add_argument("--injector-dex", help="use this prebuilt injector dex instead of compiling")
    pa.add_argument("--keystore", help="keystore to sign with (default: generated debug keystore)")
    pa.add_argument("--no-sign", action="store_true", help="do not sign (produces an unsigned APK)")
    pa.add_argument("--force", action="store_true", help="patch even if it already looks patched")
    pa.add_argument("--keep-work", action="store_true", help="keep the apktool work directory")
    pa.add_argument("--work-dir", help="use this work directory")
    return p


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "doctor":
        return cmd_doctor(args)
    if args.command == "patch":
        return cmd_patch(args)
    parser.error("unknown command")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

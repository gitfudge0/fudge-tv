#!/usr/bin/env python3
"""Build/run a framework-only benchmark against an already installed production APK.

Requires an explicit emulator serial and ro.kernel.qemu=1 before any installation.
Never installs the target APK, clears app data, seeds caches, or changes preferences.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "local.tv.launcher"
RUNNER = "local.tv.launcher.benchmark/local.tv.launcher.benchmark.NativeReleaseBenchmark"


def run(command, *, env=None, timeout=60):
    completed = subprocess.run(command, env=env, text=True, capture_output=True, timeout=timeout)
    if completed.returncode:
        raise RuntimeError(f"Command failed: {shlex.join(map(str, command))}\n{completed.stdout}{completed.stderr}")
    return completed.stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def metadata(aapt, apk):
    badging = run([aapt, "dump", "badging", apk])
    package = re.search(r"^package: (.*)$", badging, re.MULTILINE)
    if not package:
        raise RuntimeError("Cannot read production APK package/version")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package.group(1)))
    if fields.get("name") != PACKAGE or "application-debuggable" in badging:
        raise RuntimeError("Input must be the non-debuggable local.tv.launcher production APK")
    return fields


def certificate(signer, apk, env):
    report = run([signer, "verify", "--print-certs", apk], env=env)
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", report)
    if not match:
        raise RuntimeError("Cannot determine APK signing certificate")
    return match.group(1).lower()


def build(args, output, tools, android_jar, env):
    source = ROOT / "tools/native-release-benchmark"
    classes, dex = output / "classes", output / "dex"
    for directory in (classes, dex):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    javac = str(Path(env["JAVA_HOME"]) / "bin/javac") if env.get("JAVA_HOME") else "javac"
    run([javac, "--release", "8", "-classpath", android_jar,
         "-d", classes, source / "NativeReleaseBenchmark.java"], env=env)
    run([tools / "d8", "--lib", android_jar, "--min-api", "31", "--output", dex,
         *sorted(classes.rglob("*.class"))], env=env)
    unsigned, aligned, signed = output / "unsigned.apk", output / "aligned.apk", output / "native-release-benchmark.apk"
    run([tools / "aapt2", "link", "-o", unsigned, "--manifest", source / "AndroidManifest.xml",
         "-I", android_jar, "--version-code", "1", "--version-name", "1.0"], env=env)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex / "classes.dex", "classes.dex")
    run([tools / "zipalign", "-f", "4", unsigned, aligned], env=env)
    run([tools / "apksigner", "sign", "--ks", args.signing_key, "--ks-type", "PKCS12",
         "--ks-key-alias", "launcher", "--ks-pass", "env:FUDGE_BENCHMARK_STORE_PASSWORD",
         "--key-pass", "env:FUDGE_BENCHMARK_KEY_PASSWORD", "--out", signed, aligned], env=env)
    return signed


def parse_result(raw):
    records = re.findall(r"^INSTRUMENTATION_RESULT: nativeReleaseBenchmark=(.*)$", raw, re.MULTILINE)
    if len(records) != 1:
        raise RuntimeError("Missing or ambiguous instrumentation JSON result; shell exit status is not benchmark success")
    result = json.loads(records[0])
    samples = result.get("samples", [])
    valid = (result.get("success") is True and result.get("status") == "complete"
             and result.get("warmupCompletedMovements") == 60
             and result.get("measuredCompletedMovements") == 180
             and len(result.get("frozenFocusDescriptions", [])) == 11
             and len(set(result.get("frozenFocusDescriptions", []))) == 11
             and len(samples) == 4 and result.get("collectorDrained") is True
             and all(sample.get("completedMovements") == 60 and sample.get("frameCount", 0) > 0 for sample in samples))
    return result, valid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit task-local Android emulator serial")
    parser.add_argument("--apk", required=True, type=Path, help="Production APK already installed by the operator")
    parser.add_argument("--sdk-root", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk")))
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--signing-key", type=Path, default=ROOT / "launcher-signing.p12")
    parser.add_argument("--build-only", action="store_true", help="Build locally; make no ADB calls or device changes")
    args = parser.parse_args()
    args.apk, args.signing_key = args.apk.resolve(), args.signing_key.resolve()
    tools, android_jar = args.sdk_root / "build-tools" / args.build_tools, args.sdk_root / "platforms/android-35/android.jar"
    output = ROOT / ".local/native-verification/release-benchmark"
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    studio_java = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if not env.get("JAVA_HOME") and studio_java.exists():
        env["JAVA_HOME"] = str(studio_java)
    env.setdefault("FUDGE_BENCHMARK_STORE_PASSWORD", "local-launcher-build")
    env.setdefault("FUDGE_BENCHMARK_KEY_PASSWORD", env["FUDGE_BENCHMARK_STORE_PASSWORD"])
    if not args.apk.is_file() or not args.signing_key.is_file():
        raise RuntimeError("Production APK and existing signing key must exist; this tool never generates a key")
    info = metadata(tools / "aapt2", args.apk)
    production_sha = digest(args.apk)
    helper = build(args, output, tools, android_jar, env)
    if certificate(tools / "apksigner", helper, env) != certificate(tools / "apksigner", args.apk, env):
        raise RuntimeError("Benchmark and production APK signatures differ")
    build_record = {"productionApkSha256": production_sha, "versionName": info["versionName"],
                    "versionCode": info["versionCode"], "helperApkSha256": digest(helper), "helperApk": str(helper)}
    (output / "build.json").write_text(json.dumps(build_record, indent=2) + "\n")
    if args.build_only:
        print(json.dumps({"built": True, "deviceOperations": False, **build_record}, indent=2))
        return 0
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        raise RuntimeError("Refusing device operations: --serial must name a local emulator-NNNN transport")
    adb = args.sdk_root / "platform-tools/adb"

    def shell(*parts, timeout=30):
        return run([adb, "-s", args.serial, "shell", shlex.join(map(str, parts))], timeout=timeout).strip()

    if run([adb, "-s", args.serial, "get-state"]).strip() != "device" or shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Refusing device operations: explicit serial must be an online local emulator with ro.kernel.qemu=1")
    paths = shell("pm", "path", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise RuntimeError("Expected one installed production base APK; target installation is the operator's responsibility")
    installed_sha = shell("sha256sum", paths[0][8:]).split()[0].lower()
    if installed_sha != production_sha:
        raise RuntimeError("Installed target SHA differs from --apk; refusing to benchmark verification/stale code")
    surface = shell("dumpsys", "SurfaceFlinger")
    gles = next((line.strip() for line in surface.splitlines() if "GLES:" in line), "unavailable")
    context = {"serial": args.serial, "roKernelQemu": "1", "graphicsRendererHint": gles[:1000],
               "eglHardware": shell("getprop", "ro.hardware.egl"),
               "vulkanHardware": shell("getprop", "ro.hardware.vulkan"),
               "hwuiRendererProperty": shell("getprop", "debug.hwui.renderer"),
               "buildType": "actual production release", **build_record}
    import base64
    encoded = base64.b64encode(json.dumps(context).encode()).decode()
    run([adb, "-s", args.serial, "install", "-r", "--no-streaming", helper], timeout=60)
    raw = shell("am", "instrument", "-w", "-r", "-e", "apkSha256", production_sha,
                "-e", "versionName", info["versionName"], "-e", "versionCode", info["versionCode"],
                "-e", "contextBase64", encoded, RUNNER, timeout=180)
    (output / "instrumentation.txt").write_text(raw + "\n")
    result, valid = parse_result(raw)
    if result.get("apkSha256") != production_sha:
        valid = False
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    print(f"Raw instrumentation: {output / 'instrumentation.txt'}", file=sys.stderr)
    return 0 if valid else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        print(f"Benchmark unavailable: {error}", file=sys.stderr)
        raise SystemExit(2)

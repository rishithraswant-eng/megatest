"""Record real ADB device identity and available runtime evidence before benchmarking."""
import argparse
import json
import subprocess
import shutil
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--serial", help="Explicit device serial; required if several devices are connected")
    parser.add_argument("--qairt", type=Path, help="Optional local installed SDK root")
    parser.add_argument("--output", type=Path, default=Path("tmp/phase1/device-preflight.json"))
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run(*command):
        process = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=20)
        if process.returncode:
            raise RuntimeError(process.stderr.strip() or "ADB command failed")
        return process.stdout.strip()

    if run("get-state") != "device":
        raise RuntimeError("Connect and authorize the actual Android target before preflight.")
    properties = {name: run("shell", "getprop", name) for name in (
        "ro.product.manufacturer", "ro.product.model", "ro.soc.model", "ro.soc.manufacturer",
        "ro.hardware", "ro.build.version.release", "ro.build.version.sdk", "ro.build.display.id", "ro.product.cpu.abilist")}
    sdk_files = []
    if args.qairt and args.qairt.is_dir():
        sdk_files = [str(item.relative_to(args.qairt)) for item in args.qairt.rglob("libQnnHtp.so")]
    report = {
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
        "expected_target": "Snapdragon 8 Elite SM8750P", "expected_runtime": "QAIRT/SNPE 2.47",
        "properties": properties, "memory": run("shell", "cat", "/proc/meminfo"),
        "battery_snapshot": run("shell", "dumpsys", "battery"),
        "thermal_snapshot": run("shell", "dumpsys", "thermalservice"),
        "local_qnn_htp_files": sdk_files,
        "accelerator_verified": False, "benchmark_passed": False,
        "notes": "SDK files and properties do not prove that a model executed on Hexagon. Battery/thermal snapshots are not power benchmarks.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Recorded actual device evidence: {args.output}")


if __name__ == "__main__":
    main()

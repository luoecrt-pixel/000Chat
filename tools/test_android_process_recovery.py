#!/usr/bin/env python3
"""Kill the live seeded app process, then verify production WorkManager recovery.

Requires installed Debug + AndroidTest APKs on a disposable emulator. Uses
ordinary AndroidJUnitRunner arguments; never clears app data or touches a phone.
The seed test does not return normally: its marker proves a durable checkpoint
and pending WorkManager work before an external force-stop kills that process.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time

PACKAGE = "com.example.openaichat"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
FIXTURE = PACKAGE + ".ProcessDeathRecoveryFixture"
SCENARIOS = ("terminal", "expired_partial", "stopped_pre_id", "replaced")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--reports", type=Path, default=Path("app/build/reports/process-recovery"))
    args = parser.parse_args()
    if not args.serial:
        devices = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True).stdout
        matches = re.findall(r"^(emulator-\d+)\s+device$", devices, flags=re.MULTILINE)
        if len(matches) != 1:
            raise SystemExit("Select exactly one disposable emulator with --serial")
        args.serial = matches[0]
    adb = ["adb", "-s", args.serial]

    def command(*words: str, timeout: int = 30) -> str:
        result = subprocess.run(adb + list(words), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"adb command failed: {' '.join(words)}\n{result.stdout}\n{result.stderr}")
        return result.stdout.strip()

    if command("shell", "getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("Refusing process-kill tests on a physical device")
    installed = command("shell", "pm", "list", "instrumentation")
    if RUNNER not in installed:
        raise SystemExit("Debug AndroidTest APK is not installed")
    args.reports.mkdir(parents=True, exist_ok=True)
    summary: list[dict] = []

    for scenario in SCENARIOS:
        seed_args = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                     FIXTURE + "#seedAndWaitForHostProcessKill", "-e", "recoveryStage", "seed",
                     "-e", "recoveryScenario", scenario, RUNNER]
        seed = subprocess.Popen(adb + seed_args, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1)
        output: queue.Queue[str | None] = queue.Queue()
        lines: list[str] = []

        def read_seed() -> None:
            assert seed.stdout is not None
            for line in seed.stdout:
                output.put(line)
            output.put(None)

        reader = threading.Thread(target=read_seed, daemon=True)
        reader.start()
        marker = re.compile(r"PROCESS_RECOVERY_SEEDED scenario=" + re.escape(scenario) + r" pid=(\d+)")
        deadline = time.monotonic() + 90
        seed_pid = None
        try:
            while time.monotonic() < deadline:
                try:
                    line = output.get(timeout=1)
                except queue.Empty:
                    continue
                if line is None:
                    raise RuntimeError("Seed exited before the process-kill checkpoint\n" + "".join(lines))
                lines.append(line)
                match = marker.search(line)
                if match:
                    seed_pid = int(match.group(1))
                    break
            if seed_pid is None:
                raise RuntimeError("Seed did not reach its durable checkpoint")
            pids = command("shell", "pidof", PACKAGE).split()
            if str(seed_pid) not in pids:
                raise RuntimeError("Seed process already died; this is not a process-kill proof")
            command("shell", "am", "force-stop", PACKAGE)
            seed.wait(timeout=20)
            reader.join(timeout=5)
            while not output.empty():
                line = output.get_nowait()
                if line is not None:
                    lines.append(line)
            # pidof returns exit 1 when absent, so inspect it without check=True.
            pids_after = subprocess.run(adb + ["shell", "pidof", PACKAGE],
                                        capture_output=True, text=True, timeout=15).stdout.split()
            if str(seed_pid) in pids_after:
                raise RuntimeError("force-stop did not kill the seeded process")
        finally:
            if seed.poll() is None:
                command("shell", "am", "force-stop", PACKAGE)
                seed.terminate()
                seed.wait(timeout=15)
            (args.reports / f"{scenario}-seed.log").write_text("".join(lines), encoding="utf-8")

        verification = command("shell", "am", "instrument", "-w", "-r", "-e", "class",
                               FIXTURE + "#verifyProductionScheduledRecoveryInNewProcess",
                               "-e", "recoveryStage", "verify", "-e", "recoveryScenario", scenario,
                               RUNNER, timeout=150)
        (args.reports / f"{scenario}-verify.log").write_text(verification + "\n", encoding="utf-8")
        if ("OK (1 test)" not in verification or "FAILURES!!!" in verification or
                "INSTRUMENTATION_FAILED" in verification):
            raise RuntimeError(f"Recovery verification failed: {scenario}\n{verification}")
        summary.append({"scenario": scenario, "seed_pid": seed_pid,
                        "live_process_killed": True, "production_workmanager": "passed"})
        (args.reports / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"Process recovery: {scenario} PASS (live PID {seed_pid} killed; new-process WorkManager)", flush=True)


if __name__ == "__main__":
    main()

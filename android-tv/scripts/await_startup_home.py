"""Synchronize a snapshot with the actual curtain lifecycle, without accepting UI."""
import argparse
import re
import subprocess
import time
from pathlib import Path
from check_startup_probe import check_lifecycle


def await_home(read, timeout=15, clock=time.monotonic, pause=time.sleep):
    deadline = clock() + timeout
    while True:
        diagnostics = read()
        assert "FATAL EXCEPTION" not in diagnostics, "Startup crashed before snapshot"
        if re.search(r"HerrinStartup:[^\n]*activity_to_home_ms=\d+", diagnostics):
            # A completion event with invalid phases fails immediately.
            # Never poll until a broken lifecycle accidentally looks valid.
            check_lifecycle(diagnostics)
            return diagnostics
        if clock() >= deadline:
            raise TimeoutError("Interactive home completion event missing within 15s snapshot synchronization budget")
        pause(0.25)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("diagnostics", type=Path)
    args = parser.parse_args()
    def read():
        text = subprocess.run(["adb", "logcat", "-d", "-s", "HerrinStartup:I",
                               "HerrinStartupPhase:I", "AndroidRuntime:E"],
                              capture_output=True, check=True, text=True).stdout
        args.diagnostics.write_text(text)
        return text
    await_home(read)

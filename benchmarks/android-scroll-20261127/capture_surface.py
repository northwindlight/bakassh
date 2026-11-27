"""Poll the selected terminal BLAST layer during a run of measure.py."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import threading

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("layer", help="exact layer name from dumpsys SurfaceFlinger --list")
parser.add_argument("label")
parser.add_argument("measurement_args", nargs=argparse.REMAINDER, help="measure.py arguments after label")
args = parser.parse_args()
adb = os.environ.get("ADB", "adb")
stamps = set()
stop = threading.Event()

def collect():
    command = "dumpsys SurfaceFlinger --latency " + shlex.quote(args.layer)
    while not stop.is_set():
        output = subprocess.check_output([adb, "shell", command], text=True, timeout=15)
        for row in output.splitlines():
            fields = row.split()
            if len(fields) == 3 and fields[1].isdigit():
                present = int(fields[1])
                if 0 < present < 2**62:
                    stamps.add(present)
        stop.wait(.4)

from concurrent.futures import ThreadPoolExecutor
with ThreadPoolExecutor(max_workers=1) as executor:
    sample = executor.submit(collect)
    try:
        subprocess.run([sys.executable, "measure.py", args.label, *args.measurement_args], check=True)
    finally:
        stop.set()
        sample.result()
        Path(args.label + "-sf.json").write_text(json.dumps({
            "layers": [args.layer], "present_monotonic_ns": sorted(stamps)
        }), encoding="utf-8")
if not stamps:
    raise SystemExit("No presentation timestamps for this layer")

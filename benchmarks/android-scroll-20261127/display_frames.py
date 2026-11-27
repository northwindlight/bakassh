"""Summarize display presentations in device gesture intervals.

Usage: python display_frames.py run.json /remote/run.pftrace [--surface run-sf.json]
"""
import argparse
import csv
import io
import json
import math
import shlex
import statistics
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("record", type=Path)
parser.add_argument("trace", help="trace on the SSH host")
parser.add_argument("--surface", type=Path, help="SurfaceFlinger BLAST latency capture")
parser.add_argument("--host", default="rpi5")
parser.add_argument("--tp", default="~/.local/share/perfetto/prebuilts/trace_processor_shell-*")
args = parser.parse_args()

def query(sql):
    cmd = f"{args.tp} query {shlex.quote(args.trace)} {shlex.quote(sql)}"
    output = subprocess.check_output(["ssh", args.host, cmd], encoding="utf-8")
    return list(csv.DictReader(io.StringIO(output)))

record = json.loads(args.record.read_text(encoding="utf-8-sig"))
if args.surface:
    offsets = query("SELECT ts - clock_value AS delta FROM clock_snapshot WHERE clock_id = 3")
    offset = round(statistics.median(int(r["delta"]) for r in offsets))
    surface = json.loads(args.surface.read_text(encoding="utf-8-sig"))
    times = sorted(set(t + offset for t in surface["present_monotonic_ns"]))
else:
    if record["package"] != "dev.northwind.bakassh":
        parser.error("Display-token query selects the bakaSSH MainActivity layer")
    rows = query("""SELECT DISTINCT d.ts + d.dur AS t
        FROM actual_frame_timeline_slice a JOIN process p ON a.upid = p.upid
        JOIN actual_frame_timeline_slice d ON a.display_frame_token = d.display_frame_token
        WHERE d.surface_frame_token IS NULL AND d.display_frame_token > 0 AND d.dur > 0
        AND p.name = 'dev.northwind.bakassh' AND a.layer_name LIKE '%MainActivity%'
        AND a.present_type IN ('Late Present', 'Early Present', 'On-time Present')""")
    times = sorted(int(r["t"]) for r in rows)

def percentile(values, fraction):
    if not values:
        return None
    i = (len(values) - 1) * fraction
    v = values[math.floor(i)] + (i % 1) * (values[math.ceil(i)] - values[math.floor(i)])
    return round(v / 1e6, 3)

results = []
for case in record["cases"]:
    for kind in ("phase", "stroke"):
        frames, duration, intervals = 0, 0, []
        for event, _, start, end in case.get("times", []):
            if event == kind:
                group = [t for t in times if start <= t < end]
                frames += len(group)
                duration += end - start
                intervals.extend(b-a for a,b in zip(group, group[1:]))
        if duration:
            intervals.sort()
            results.append(dict(target=case["target"], interval=kind, frames=frames,
                                duration_ns=duration, fps=round(frames*1e9/duration, 2),
                                p50_ms=percentile(intervals, .5), p95_ms=percentile(intervals, .95)))
print(json.dumps(results, indent=2))

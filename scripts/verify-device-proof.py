"""Reject device runs that omit actual scenario screenshots or runner outcomes."""
import json
import re
import struct
import sys
from pathlib import Path

api = int(sys.argv[1])
root = Path("ci-artifacts")
counts = []
for name in ("01-rooms-tasks-session.txt", "02-process-restart-history.txt", "03-font-200-percent.txt"):
    report = (root / name).read_text()
    match = re.search(r"OK \((\d+) tests?\)", report)
    if not match or int(match[1]) < 1:
        raise SystemExit(f"Missing successful instrumentation results: {name}")
    counts.append({"report": name, "tests": int(match[1])})
screenshots = sorted((root / "screenshots").rglob("*.png"))
required = {f"01-session-api-{api}.png", f"02-history-en-api-{api}.png", f"02-history-ru-api-{api}.png", f"03-font-200-api-{api}.png"}
if not required.issubset({path.name for path in screenshots}):
    raise SystemExit("Required real CI screenshots were not collected")
for path in screenshots:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) < 2048:
        raise SystemExit(f"Invalid or empty screenshot: {path}")
    width, height = struct.unpack(">II", data[16:24])
    if width < 300 or height < 300:
        raise SystemExit(f"Screenshot dimensions are too small: {path}")
(root / "device-summary.json").write_text(json.dumps({"api": api, "font_scale_tested": 2.0, "runner_results": counts, "screenshots": [str(path) for path in screenshots]}, indent=2) + "\n")

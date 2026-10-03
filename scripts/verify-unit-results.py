"""Require meaningful JUnit results, fail on failures/errors/skips, record counts."""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

files = sorted(Path(sys.argv[1]).glob("TEST-*.xml"))
counts = dict(tests=0, failures=0, errors=0, skipped=0)
suites = []
for path in files:
    root = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(root.attrib.get(key, 0))
    suites.append(root.attrib.get("name", path.stem))
report = {**counts, "suites": suites}
destination = Path(sys.argv[2])
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
if counts["tests"] < 10 or any(counts[key] for key in ("failures", "errors", "skipped")):
    raise SystemExit("At least 10 actual passing unit tests and zero failures/errors/skips required")

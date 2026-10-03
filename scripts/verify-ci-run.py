"""Authorize signing only after the same source SHA passed every Android gate."""
import json
import os
import re
import subprocess
from pathlib import Path

run_id = os.environ["HOME_SESSION_CI_RUN_ID"]
if not re.fullmatch(r"[1-9][0-9]*", run_id):
    raise SystemExit("A valid prior Android CI run ID is required")
repository = os.environ["GITHUB_REPOSITORY"]
if repository != "jonkryl/home-session":
    raise SystemExit("Signing is limited to the authorized personal Home Session repository")

def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))

run = api(f"repos/{repository}/actions/runs/{run_id}")
if run["head_sha"] != os.environ["GITHUB_SHA"] or run["status"] != "completed" or run["conclusion"] != "success":
    raise SystemExit("The prior CI run must have succeeded for this exact source commit")
if run["path"] != ".github/workflows/android-ci.yml":
    raise SystemExit("The supplied run is not the Home Session Android quality workflow")
jobs = api(f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100")["jobs"]
required = {"Unit tests and Android lint", "Android API 24 journey", "Android API 36 journey", "All checks passed"}
successful = {job["name"] for job in jobs if job["conclusion"] == "success"}
if not required.issubset(successful):
    raise SystemExit(f"Missing successful required jobs: {sorted(required - successful)}")
destination = Path("release-output")
destination.mkdir(exist_ok=True)
(destination / "quality-gates.json").write_text(json.dumps({
    "source_sha": run["head_sha"], "ci_run_id": int(run_id), "ci_run_url": run["html_url"],
    "jobs": [{"name": job["name"], "conclusion": job["conclusion"], "url": job["html_url"]} for job in jobs],
}, indent=2) + "\n")
print(f"All required gates passed: {run['html_url']}")

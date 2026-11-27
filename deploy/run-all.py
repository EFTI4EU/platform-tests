#!/usr/bin/env python3
"""
Runs the test suite against the pseudo-platform without a human: plays the operator of `sh tst.sh run`, as described
in "Driving a run from a script" in testdata/README.md. For checking the test suite itself, not a real platform.

Usage: python3 deploy/run-all.py [data set keys, default: all with test data]
Exit code: the runner's (0 passed, 1 a test case failed, 2 technical error).
"""
import contextlib
import html
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEPLOY_DIR = Path(__file__).resolve().parent
REPO_DIR = DEPLOY_DIR.parent
PLATFORM_URL = f"http://localhost:{os.environ.get('TST_PLATFORM_PORT', '18090')}"
TECHNICAL_ERROR = 2
DATA_SET_ID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
STEP = re.compile(r">>> \[([^\]]+)\] Step (\d) of 4")
# Requests to the local page must not go through a proxy configured on the machine.
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def read_catalogue() -> dict[str, dict[str, str]]:
    """The data sets of testdata/datasets.yml that have test data, in file order, as flat fields by key."""
    blocks = re.split(r"^  - key: ", (REPO_DIR / "testdata" / "datasets.yml").read_text(encoding="utf-8"), flags=re.M)[1:]
    entries = [dict(re.findall(r'^\s*(\w+): *"?([^"\n#]+?)"? *(?:#.*)?$', "key: " + block, re.M)) for block in blocks]
    return {entry["key"]: entry for entry in entries if "testData" in entry}


def test_data_dir() -> Path:
    """The test data folder of the format the pseudo-platform's runner configuration uses."""
    config = (DEPLOY_DIR / "platforms" / "pseudoplatform" / "tst.yml").read_text(encoding="utf-8")
    return REPO_DIR / "formats" / re.search(r"^formatDir: .*/(\S+)$", config, re.M).group(1) / "testdata"


def page() -> str:
    with HTTP.open(PLATFORM_URL + "/", timeout=30) as response:
        return response.read().decode()


def post(path: str, **form: str) -> None:
    request = urllib.request.Request(PLATFORM_URL + path, data=urllib.parse.urlencode(form).encode())
    with HTTP.open(request, timeout=30) as response:
        response.read()


def data_set_ids(body: str) -> set[str]:
    return set(re.findall(rf"/datasets/({DATA_SET_ID})/carrier-signature", body))


def create_data_set(xml: str) -> str:
    before = data_set_ids(page())
    post("/datasets", xml=xml)
    created = data_set_ids(page()) - before
    if len(created) != 1:
        raise RuntimeError("the pseudo-platform did not create the data set")
    return created.pop()


def correct_transport_means(data_set_id: str, typo: str, corrected: str) -> None:
    form = re.search(rf'/datasets/{data_set_id}/update".*?<textarea[^>]*>(.*?)</textarea>', page(), re.S)
    if form is None:
        raise RuntimeError(f"the pseudo-platform offers no update of data set {data_set_id}")
    post(f"/datasets/{data_set_id}/update", xml=html.unescape(form.group(1)).replace(typo, corrected))


def operate(runner: subprocess.Popen, catalogue: dict[str, dict[str, str]]) -> None:
    """Does each step the runner asks for and answers it; the runner goes on by itself in between."""
    ids: dict[str, str] = {}
    for line in runner.stdout:
        print(line, end="", flush=True)
        step = STEP.search(line)
        if step is None:
            continue
        key, number = step.group(1), step.group(2)
        entry = catalogue[key]
        answer = ""
        if number == "1":
            ids[key] = answer = create_data_set((test_data_dir() / f"{entry['testData']}.xml").read_text(encoding="utf-8"))
        elif number == "2":
            post(f"/datasets/{ids[key]}/carrier-signature")
        elif number == "3":
            correct_transport_means(ids[key], entry["transportMeansTypo"], entry["transportMeansCorrected"])
        else:
            post(f"/datasets/{ids[key]}/consignee-signature")
        runner.stdin.write(answer + "\n")
        runner.stdin.flush()


def main(keys: list[str]) -> int:
    catalogue = read_catalogue()
    unknown = sorted(set(keys) - catalogue.keys())
    if unknown:
        print(f"Unknown data sets: {', '.join(unknown)}; available: {', '.join(catalogue)}", file=sys.stderr)
        return TECHNICAL_ERROR
    try:
        post("/clear")
    except urllib.error.URLError as e:
        print(f"Pseudo-platform not reachable at {PLATFORM_URL} ({e}); start it with: sh tst.sh start", file=sys.stderr)
        return TECHNICAL_ERROR
    runner = subprocess.Popen(["sh", "tst.sh", "run", *(keys or catalogue)], cwd=DEPLOY_DIR, stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    try:
        operate(runner, catalogue)
    except (OSError, RuntimeError) as e:
        print(f"Operator step failed: {e}", file=sys.stderr)
        # Closing the standard input stops the run with a technical error, which still writes the report.
        with contextlib.suppress(OSError):
            runner.stdin.close()
        for line in runner.stdout:
            print(line, end="", flush=True)
        runner.wait()
        return TECHNICAL_ERROR
    return runner.wait()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

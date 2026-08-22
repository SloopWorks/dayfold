#!/usr/bin/env bash
# Print the FAILING tests from Gradle's JUnit XML, by name, with their message.
#
# Why this exists: CI's `if: failure()` diagnostics covered golden-image mismatches
# (the snapshot dashboard) but nothing else. When a plain unit test failed, the run
# page showed only "Execution failed for task ':client:desktopTest'" and the answer —
# WHICH of ~1000 tests failed — was buried in the raw log (~160 KB for #390, and only
# reachable by downloading it). Gradle already writes every result to XML; this reads
# it and puts the answer on the run's summary page.
#
# Diagnostic only: always exits 0 so it can never mask, or become, the real failure.
set -uo pipefail
cd "$(dirname "$0")/.."

ROOTS=("${@:-apps}")

python3 - "${ROOTS[@]}" <<'PY'
import os, sys, xml.etree.ElementTree as ET

files = []
for root in sys.argv[1:]:
    for dirpath, _, names in os.walk(root):
        if os.path.sep + "test-results" + os.path.sep not in dirpath + os.path.sep:
            continue
        files += [os.path.join(dirpath, n) for n in names
                  if n.startswith("TEST-") and n.endswith(".xml")]

tests = failed = skipped = unreadable = 0
bad = []
# Per-Gradle-task tallies. A step killed by its timeout leaves COMPLETE xml for the
# tasks that finished and NOTHING for the ones still running — indistinguishable from
# a clean run by totals alone, but obvious the moment you see which tasks reported.
by_task = {}
for path in sorted(files):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        # A suite killed mid-write (step timeout, OOM) leaves truncated XML. Say so —
        # otherwise the summary reports "0 failed" and reads like a clean run.
        unreadable += 1
        continue
    n = int(suite.get("tests") or 0)
    tests += n
    skipped += int(suite.get("skipped") or 0)
    # .../<module>/build/test-results/<task>/TEST-*.xml
    parts = path.split(os.path.sep)
    task = parts[-2] if len(parts) >= 2 else "?"
    module = parts[-5] if len(parts) >= 5 else "?"
    by_task[f"{module}:{task}"] = by_task.get(f"{module}:{task}", 0) + n
    for case in suite.iter("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is None:
                continue
            failed += 1
            detail = (node.get("message") or node.text or "").strip()
            # Gradle appends "()" to backticked Kotlin test names; strip a BARE pair
            # so the name reads as written (a parameterized "foo(int)[1]" is left alone).
            case_name = case.get("name", "?")
            if case_name.endswith("()"):
                case_name = case_name[:-2]
            bad.append((case.get("classname", "?"), case_name, kind,
                        "\n".join(detail.splitlines()[:12])))
            break

if not files:
    sys.exit(0)  # nothing ran — the build failed before tests (compile, resolve)

lines = [f"### Test failures — {failed} failed, {tests} run, {skipped} skipped", ""]
if unreadable:
    lines += [f"{unreadable} result file(s) were truncated — that suite was killed "
              "mid-write (step timeout or OOM), so its failures are not listed here.", ""]
if not bad and not unreadable:
    # Deliberately does NOT assert a cause. On its first live firing this said "the build
    # failed outside a test" when the step had actually timed out with two suites still
    # running — the tally below is what makes that visible, so state the fact and stop.
    lines.append("No failing test case in the XML. The build failed without a test "
                 "failing — it was cut short (step timeout, OOM, runner loss) or broke "
                 "outside the tests (compile, task wiring). The step's own error line "
                 "above says which; the tally below shows how far it got.")
for classname, name, kind, detail in bad:
    lines += [f"**{classname.rsplit('.', 1)[-1]} > {name}**  (`{kind}`)", ""]
    if detail:
        lines += ["```", detail, "```", ""]

if by_task:
    lines += ["", "Results reported per task — a task that ran but is missing here never "
                  "finished:", ""]
    lines += [f"- `{k}` — {v} tests" for k, v in sorted(by_task.items())]

out = "\n".join(lines)
print(out)
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as fh:
        fh.write(out + "\n")
PY
exit 0

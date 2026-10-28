#!/usr/bin/env python3
"""List the tests that did not run, and why, for a CI run's summary.

The ROM-gated suites skip when their environment is missing (a ROM, a core, a save state). Gradle's log only *names* a
skip; the reason is in the JUnit XML the test task writes, so that is what this reads. Prints markdown, exits 0 either
way: no report at all means the tests never got that far, which the job's own failure already says.
"""

import glob
import re
import xml.etree.ElementTree as ET

RESULTS = "build/test-results/test/*.xml"

# JUnit writes the whole assertion line ("org.opentest4j.TestAbortedException: Assumption failed: PAUSE_MUSIC_ROM is not
# set…"): the reason is the sentence after the exception and the JUnit wording, which is all noise in a summary.
NOISE = re.compile(r"^(?:[\w.$]*Exception: )?(?:Assumption failed: )?")


def skipped(paths):
    """(test name, reason) for every skipped test in the JUnit reports."""
    for path in sorted(paths):
        for case in ET.parse(path).getroot().iter("testcase"):
            mark = case.find("skipped")
            if mark is None:
                continue
            reason = " ".join((mark.get("message") or mark.text or "").split())
            name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
            yield name, NOISE.sub("", reason)


def main() -> None:
    rows = list(skipped(glob.glob(RESULTS)))
    if not rows:
        print("Nothing was skipped.")
        return
    print(f"{len(rows)} test(s) did not run:")
    print()
    for name, reason in rows:
        print(f"- `{name}` — {reason}" if reason else f"- `{name}`")


if __name__ == "__main__":
    main()

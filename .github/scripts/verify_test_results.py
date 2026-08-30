#!/usr/bin/env python3
"""Fail when a test run reported less than it was supposed to verify.

The Redis suites are annotated `@Testcontainers(disabledWithoutDocker = true)`, which is
the right behavior on a laptop with no Docker daemon and the wrong behavior on CI: a
disabled class is reported as a skip, the Gradle build still succeeds, and the run goes
green having verified none of the Redis behavior. Gradle has no opinion on skips, so the
check has to live outside it.

Run from the repository root, after the test task.
"""

import glob
import sys
import xml.etree.ElementTree as ET

results = sorted(glob.glob("**/build/test-results/test/*.xml", recursive=True))
if not results:
    sys.exit("no test result files found: the test task never ran")

total = 0
skipped = 0
skipped_classes = []

for path in results:
    suite = ET.parse(path).getroot()
    total += int(suite.get("tests", 0))
    count = int(suite.get("skipped", 0))
    skipped += count
    if count:
        skipped_classes.append(f"{suite.get('name')} ({count})")

print(f"{len(results)} test classes, {total} tests, {skipped} skipped")

if total == 0:
    sys.exit("test results contain no tests at all")

if skipped:
    sys.exit(
        "a skipped test is not a pass on CI. Skipped: "
        + ", ".join(skipped_classes)
        + "\nIf the Redis suites are here, the Docker daemon was unreachable."
    )

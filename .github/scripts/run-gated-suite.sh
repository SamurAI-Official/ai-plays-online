#!/bin/bash
# Runs one gated suite in its own JVM (a libretro core can only be started once per process) and archives its JUnit XML.
#
# The `test` task cleans build/test-results on every run, so without archiving, seven suites in one job would leave only
# the last one's results behind — and the run's summary would say nothing about the six that came before.
#
# usage: run-gated-suite.sh <label> <--tests filter> [ENV=value ...]
set -u
label="$1"; shift
filter="$1"; shift
results="${RUNNER_TEMP:-/tmp}/results/$label"
mkdir -p "$results"

status=0
env "$@" ./gradlew --no-daemon test --tests "$filter" || status=$?
cp build/test-results/test/*.xml "$results/" 2>/dev/null || true
exit "$status"

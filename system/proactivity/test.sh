#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/host-tests
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -d out/host-tests contract/org/agentos/proactivity/api/ObserverSpec.java contract/org/agentos/proactivity/api/PromptSpec.java src/org/agentos/proactivity/SettledObserver.java tests/SettledObserverTest.java src/org/agentos/proactivity/OverviewStats.java tests/OverviewStatsTest.java
"$task_java" -cp out/host-tests org.agentos.proactivity.SettledObserverTest
"$task_java" -cp out/host-tests org.agentos.proactivity.OverviewStatsTest
bash test-prompts.sh

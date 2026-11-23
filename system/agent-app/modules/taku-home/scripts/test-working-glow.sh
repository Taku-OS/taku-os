#!/usr/bin/env bash
# Host-only working-glow envelope regression; requires JDK 17+, Kotlin and cached JUnit 4.13.2.
set -euo pipefail
MODULE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$MODULE/../../../.." && pwd)
source "$ROOT/scripts/android-env.sh"
source "$ROOT/scripts/compile-jvm.sh"
CP=$(python3 - <<'PYTHON'
import os
from pathlib import Path
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
jars = []
for module in ['junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3']:
    matches = list((cache / module).glob('*/*.jar'))
    if not matches:
        raise SystemExit('Missing cached dependency: ' + module + '; run the Android build first.')
    jars.append(str(matches[0]))
print(':'.join(jars))
PYTHON
)
CLASSES="$ROOT/out/working-glow-tests"
compile_jvm "$CLASSES" "$CP" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/ui/effects/WorkingGlowClock.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/WorkingGlowClockTest.kt"
"$JDK/bin/java" -cp "$CLASSES:$CP:$KOTLIN_LIB/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore org.agentos.agent.home.WorkingGlowClockTest

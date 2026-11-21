#!/usr/bin/env bash
# Host-only admission/lease regression. Reuses the Android build's Kotlin/JUnit toolchain.
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
for module, override in [('junit/junit/4.13.2', 'TAKU_TEST_JUNIT_JAR'), ('org.hamcrest/hamcrest-core/1.3', 'TAKU_TEST_HAMCREST_JAR')]:
    if os.environ.get(override):
        path = Path(os.environ[override])
        if not path.is_file():
            raise SystemExit('Missing test dependency: ' + override)
        jars.append(str(path))
        continue
    matches = list((cache / module).glob('*/*.jar'))
    if not matches:
        raise SystemExit('Missing cached dependency: ' + module + '; run the Android build first.')
    jars.append(str(matches[0]))
print(':'.join(jars))
PYTHON
)
CLASSES="$ROOT/out/character-tryon-tests"
compile_jvm "$CLASSES" "$CP" \
  "$ROOT/system/tryon/contract/org/taku/cosplay/api/CosplayContract.java" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/CharacterTryOnPolicy.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/CharacterTextInvocation.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/ui/assistant/ComposerImeDismiss.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/CharacterTryOnPolicyTest.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/CharacterTextInvocationTest.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/ComposerImeDismissTest.kt"
"$JDK/bin/java" -cp "$CLASSES:$CP:$KOTLIN_LIB/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore org.agentos.agent.home.CharacterTryOnPolicyTest org.agentos.agent.home.CharacterTextInvocationTest org.agentos.agent.home.ComposerImeDismissTest

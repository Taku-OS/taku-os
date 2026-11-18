#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export JAVA_HOME=${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}
bash system/plugins/build-host.sh "$tmp/build"
"$JAVA_HOME/bin/javac" --release 17 -cp "$tmp/build/taku-godmode-host.jar" -d "$tmp/tests" system/plugins/tests/org/taku/plugins/host/ManagerTest.java system/plugins/tests/org/taku/plugins/host/LocalDeviceTest.java system/plugins/tests/org/taku/plugins/host/CraftIntegrationTest.java
"$JAVA_HOME/bin/java" -cp "$tmp/build/taku-godmode-host.jar:$tmp/tests" org.taku.plugins.host.ManagerTest "$tmp/build/examples"
"$JAVA_HOME/bin/java" -cp "$tmp/build/taku-godmode-host.jar:$tmp/tests" org.taku.plugins.host.LocalDeviceTest
"$JAVA_HOME/bin/java" -cp "$tmp/build/taku-godmode-host.jar:$tmp/tests" org.taku.plugins.host.CraftIntegrationTest
python3 system/plugins/tests/runtime-pack-test.py "$JAVA_HOME/bin/java" "$tmp/build/taku-godmode-host.jar"
bash system/plugins/tests/taku-adb-test.sh
python3 system/plugins/tests/hello-world-test.py
python3 system/plugins/tests/service-ready-test.py
python3 system/plugins/tests/cli-integration.py "$tmp/build"
python3 system/plugins/tests/rea-provision-test.py

#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
destination=${1:?usage: build-host.sh OUTPUT_DIRECTORY}
destination=$(mkdir -p "$destination" && cd "$destination" && pwd)
javac_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
jar_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
mkdir -p "$destination/classes"
api=()
while IFS= read -r source; do api+=("$source"); done < <(find system/plugins/src -name '*.java' ! -name DexPluginLoader.java | sort)
host=()
while IFS= read -r source; do host+=("$source"); done < <(find system/plugins/host -name '*.java' | sort)
"$javac_cmd" --release 17 -d "$destination/classes" "${api[@]}" "${host[@]}"
"$jar_cmd" --create --file "$destination/taku-godmode-host.jar" -C "$destination/classes" .
for name in status-bar settings-page launcher-grid; do
  mkdir -p "$destination/examples/$name" "$destination/example-classes/$name"
  "$javac_cmd" --release 17 -cp "$destination/taku-godmode-host.jar" -d "$destination/example-classes/$name" examples/godmode/"$name"/*.java
  "$jar_cmd" --create --file "$destination/examples/$name/plugin.dex" -C "$destination/example-classes/$name" .
  cp examples/godmode/"$name"/manifest.json "$destination/examples/$name/manifest.json"
done
echo "Built host and three JVM example packages in $destination"

#!/system/bin/sh
set -eu
PLUGIN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CLASSPATH="$PLUGIN_DIR/country-provider.jar"
exec /system/bin/app_process /system/bin org.agentos.environment.plugin.CountryProvider

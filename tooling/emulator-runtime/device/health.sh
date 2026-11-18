#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
. /data/local/agentos/dev-runtime/common.sh
load_config
check_all
printf 'HEALTHY\n'

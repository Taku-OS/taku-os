# Verify Arrival on an isolated Android 16 emulator

Use a disposable AOSP Android 16 userdebug AVD with the public AOSP platform test key.
The test installs two ordinary apps and resets their test data and Taku permission entries.
It uses Android's GPS provider and injects coordinates through the emulator console.

Set the SDK and JDK, then run from the repository root. Use the actual serial and AVD
name for this test. The Assistant APK and TaskBroker JAR can come from an existing
compatible Taku build or from [the Assistant build script](../../../assistant/build.sh).

```bash
set -e
export ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
SERIAL=emulator-5580
AVD=taku_arrival_events_20261120
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]]
test "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu)" = 1
test "$("$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name)" = "$AVD"
test "$("$ADB" -s "$SERIAL" shell cmd location is-location-enabled)" = true

system/environment-signals/arrival/build.sh
system/environment-signals/arrival/tests/build.sh

"$ADB" -s "$SERIAL" install --no-incremental -r system/assistant/out/AgentOSAssistant.apk
"$ADB" -s "$SERIAL" push system/assistant/out/task-broker.jar /data/local/tmp/taku-arrival-test-broker.jar
"$ADB" -s "$SERIAL" shell "su 0 sh -c 'CLASSPATH=/data/local/tmp/taku-arrival-test-broker.jar nohup app_process /system/bin org.agentos.lab.TaskBroker >/data/local/tmp/taku-arrival-test-broker.log 2>&1 </dev/null &'"
"$ADB" -s "$SERIAL" shell "content call --uri content://org.agentos.assistant.permissions --method read --arg '{}'"

"$ADB" -s "$SERIAL" install --no-incremental -r system/environment-signals/arrival/out/TakuArrival.apk
PACKAGE=org.agentos.environment.arrival
"$ADB" -s "$SERIAL" shell cmd package unstop "$PACKAGE"
for PERMISSION in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  "$ADB" -s "$SERIAL" shell pm grant "$PACKAGE" "android.permission.$PERMISSION"
done
python3 system/environment-signals/arrival/tests/device.py "$SERIAL" "$AVD"
```

Expect two distinct Android UIDs and one Android notification per app while the
keyguard shows and the screen is off. The script checks denied access before a Taku
grant, cancellation, expiry, per-app event IDs, isolation of acknowledgments, and
redelivery with the same ID before acknowledgment. It temporarily allows the Arrival
package to receive unthrottled background location and restores the previous setting.

The reboot, boundary, and host-permission checks can be run separately through the
Arrival provider. Save the previous value of the global
`location_background_throttle_package_whitelist` setting and temporarily set it to
`org.agentos.environment.arrival` on the disposable emulator. Restore it after the run.
Register a shell watch, reboot with a test screen lock, and check
`dumpsys location` for the host request before and after the first eligible unlock.
After unlock, test a 200 m radius at latitude `37.7931` and longitude `-122.3932`.
Set an outside point, an inside point, a point on the boundary (latitude `37.7949`,
same longitude), and two inside points. Wait for each GPS sample to reach the host.
`pending` should return an event after the final inside point. Leave it pending,
revoke the host's background location permission, and call `pending` again. Expect
`LOCATION_PERMISSION_REQUIRED`. Grant the permission and verify that `pending` and
`list` are empty. If revocation and regrant happen between authorization checks,
the earlier watch can remain active.

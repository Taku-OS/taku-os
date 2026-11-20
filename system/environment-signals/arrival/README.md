# Location arrival events

A caller can monitor one visit to a saved place. The host requests signals from
Android's GPS provider and saves one arrival event when the device travels
from outside to inside. Notebook can associate a place ID with saved items.
An itinerary app can use the same API to show its plans for that stop.

## Access

Use `content://org.agentos.environment.arrival` with `ContentResolver.call` or the
Agent shell. Arguments are a JSON object in the string argument. The returned
Bundle contains a JSON string under `result`, with either `{ok:true,result:{…}}`
or `{ok:false,code,message}`.

The host takes the caller's Android UID from Binder. Each app's watches and
events belong to its UID, signed package and installation. Shell, root,
system and individually identified platform-signed packages have direct
access. Other apps need precise and background Android location grants plus
`taku.control` in Taku's App Permission Center. The host checks access before
accepting a call, evaluating a fix or dispatching the event. A denied watch is
removed when the host observes the revocation. A temporarily unreachable
permission center pauses that app's watches.

The development ROM installs `TakuArrival` with revocable Android location
grants. Users can turn off its location access in Android Settings. The
package-specific background sampling policy keeps the platform's ordinary
background interval from overriding the host's ten-second request. Android
still controls location availability and delivery. Revoking the host grant
clears its watches when the host observes the revocation. A revoke and regrant
between checks can leave an earlier watch active.

## Register and receive

| Method | Argument | Result |
| --- | --- | --- |
| `register` | `id`, `placeId`, numeric `latitude`, `longitude`, `radiusMeters`, `expiresAtMs`, and `receiverClass` for Android apps | Watch details |
| `list` | `{}` | `watches` for the caller, including completed watches until expiry |
| `pending` | `{}` | Unacknowledged `events` for the caller, each with `eventId`, `id`, `placeId`, and `arrivedAtMs` |
| `ack` | `eventId` | `acknowledged` until the watch is cancelled or expires |
| `cancel` | `id` | `cancelled`. Also removes any pending event for that watch. |

IDs are caller-chosen strings of 1–128 characters. Radii are 100–5000 meters.
Expiry is a Unix timestamp in milliseconds, in the next 24 hours. An app can
keep up to 16 watches. The host holds up to 128. Repeat a registration with the
same ID and arguments to retry it safely. To change the arguments, cancel
that watch and register again.

A watch needs a confirmed outside fix followed by two consecutive confirmed
inside fixes within 90 seconds of each other. An inside fix's accuracy radius
must fit within the watch. The outside fix's accuracy radius must clear an
additional 25 meters. Uncertain fixes break the inside streak. The host drops
stale and out-of-order fixes and stores no location history. One watch
produces at most one arrival event. Watches and pending events survive process
death. Monitoring resumes after the first unlock after reboot.

Android app clients declare the authority and their receiver in the manifest.
Use the fully qualified receiver class in `register`. The protected, explicit
broadcast carries the action only. Use it as a signal to read `pending`.

```xml
<queries>
    <provider android:authorities="org.agentos.environment.arrival"/>
</queries>
<!-- Inside application -->
<receiver android:name=".ArrivalReceiver" android:exported="true"
    android:permission="org.agentos.permission.SEND_LOCATION_ARRIVAL">
    <intent-filter>
        <action android:name="org.agentos.environment.arrival.AVAILABLE"/>
    </intent-filter>
</receiver>
```

Use the stable `eventId` for idempotent handling, then acknowledge the event.
For an Android notification, use `eventId` as the notification tag to update
the same notification on a retry. The host retries the wakeup while the event
is pending and the watch is valid. Android can defer the wakeup in idle mode.
Apps can also read pending events at startup. The app controls notification
text, lock-screen visibility and click behavior.

Errors include `INVALID_ARGUMENT`, `PERMISSION_DENIED`, `UNAVAILABLE`,
`LOCATION_PERMISSION_REQUIRED`, and `LOCATION_UNAVAILABLE` when there is no
GPS provider. Android's location toggle can pause fixes. Detection depends
on a usable satellite fix and Android scheduling.

## Development build

Build the independent APK with SDK 36, Build Tools 36 and JDK 17 or newer.

```sh
bash system/environment-signals/arrival/build.sh
```

The output is `out/TakuArrival.apk` under this directory. On an isolated AOSP
emulator, grant location to the host after installing the APK. The development
ROM applies the package's default permissions and location sampling policy
during image integration.

```sh
adb -s "$SERIAL" shell pm grant org.agentos.environment.arrival android.permission.ACCESS_COARSE_LOCATION
adb -s "$SERIAL" shell pm grant org.agentos.environment.arrival android.permission.ACCESS_FINE_LOCATION
adb -s "$SERIAL" shell pm grant org.agentos.environment.arrival android.permission.ACCESS_BACKGROUND_LOCATION
```

See the [emulator verification guide](tests/README.md) for the two authorized apps
and the restart, boundary, and permission checks.

# Cellular network country observation

For GPS-based place monitoring, see [location arrival events](arrival/README.md).

This small Android Java SDK observes the country associated with the current cellular network. The caller owns its hosting process and lifecycle. It targets Android 36, matching this workspace.

Cases using existing God Mode tools should prefer the [country observation plugin](plugin/README.md). It reuses this SDK for reading, subscribing, polling, and unsubscribing; the existing PluginManager manages enabling, disabling, and reloading. Direct app or plugin SDK integration is documented below.

The production constructor reads only [`TelephonyManager.getNetworkCountryIso()`](https://developer.android.com/reference/android/telephony/TelephonyManager#getNetworkCountryIso()). Android documents this country code as derived from the MCC (mobile country code) of the currently registered operator or nearby cell, and it may be empty when unavailable. It does not represent GPS position, IP location, or SIM home country, and cannot establish that a flight has landed. The SDK uses the default TelephonyManager and does not combine multiple SIM observations to estimate location.

```java
// Create objects, call APIs, and receive callbacks on the Android main thread.
NetworkCountryObserver countries = new NetworkCountryObserver(context);
NetworkCountryObserver.Subscription subscription = countries.subscribe(observation -> {
    if (observation.availability() == CountryObservation.Availability.UNKNOWN) {
        showUnavailable(observation.reason());
        return;
    }
    showCountry(observation.countryIso(), observation.stable(),
            observation.source(), observation.observedAtMillis());
});
CountryObservation cached = countries.latest(); // Last sample; does not reread the source.
CountryObservation fresh = countries.refresh(); // Sample now and notify active subscribers.
subscription.close(); // Other subscriptions continue.
countries.close(); // Ends this observer; no further subscription or refresh is allowed.
```

`CountryObservation` is immutable. `countryIso()` returns a two-letter uppercase country code, or an empty string when unknown. `source()` is always `cellular_network`; `sourceDescription()` explains its meaning and limits. `reason()` distinguishes an empty country, invalid code, permission denial, unsupported cellular capability, and other read failures. An unknown current source is never filled with a historical country.

`observedAtMillis()` records wall-clock sampling time. `observedElapsedRealtimeMillis()` and `continuousSinceElapsedRealtimeMillis()` use `SystemClock.elapsedRealtime()`, including device sleep and restarting after reboot. The continuous observation start is `-1` when the country is unknown. `stable()` becomes true only after observations of the same country span 60 seconds without an observed unknown state, read error, or other country. Sampling cannot prove the wireless network remained unchanged between observations.

The first subscription registers a receiver for network-country changes, `USER_PRESENT`, and `SCREEN_ON`, then samples immediately. Broadcast content cannot specify the country; every wakeup rereads TelephonyManager. A known but not yet stable country is sampled again at the end of the 60-second window. Stable or unknown states are checked every 15 minutes. Explicit refresh and matching broadcasts also sample immediately. Each refresh synchronously notifies active subscribers. Adding a subscription refreshes and notifies all active subscribers. `latest()` returns a timestamped cached snapshot, even after closure, without guaranteeing freshness.

Closing a subscription is idempotent and affects only that subscription. When the last subscription closes, the SDK unregisters the receiver, removes timers, and clears the continuous observation start; a later subscription starts timing again. Closing the observer closes every subscription. Queued broadcasts and timers check validity before sampling and dispatch. Canceling a subscription within another callback prevents its later delivery in the same round. Refreshing inside a callback creates a new sample round and invalidates unfinished delivery from the old round.

For background use, place the observer in an Android-permitted host such as an existing foreground Service and close it when that host stops. The SDK does not start or keep processes alive, request a foreground Service, or provide a Binder queue or reliable delivery across process death. After host recreation, create a new observer, reread the source, and recompute stability. Applications must persist country baselines, user authorization, offer IDs, and cooldowns themselves. Old snapshots are not replayed as new events. Receiving an observation does not authorize opening the microphone or starting a user session.

Controlled checks can inject a `Source`, monotonic clock, and wall clock through the four-argument constructor. The production constructor always uses TelephonyManager.

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk JAVA_HOME=/path/to/jdk bash build.sh
```

The output is `out/taku-environment-signals.jar`. Add it to both the javac classpath and D8 input. The existing Sony client's `build.py` first builds this library, configures both dependencies, and records the artifact hash. Existing `tools/environment-check` checks the same SDK and business classes directly.

## Existing Sony consumer

`TravelEnvironment` retains application-specific rules: the initial country baseline, stable Japanese network and unlocked-device conditions, successful publication ACK, 24-hour suppression, two-minute offer validity, and retries without ACK. Its `close()` ends the current observation round. Explicit `start()` creates a new SDK instance and restarts stability timing. Persisted ACK state survives restarts; old in-memory offers do not recover. Simulation remains at the application layer and cannot modify real observations or real ACK state.

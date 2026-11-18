# Taku desktop widget applications

`TakuClock` (`org.taku.widgets`) supplies standard RemoteViews Clock, Next alarm, Date,
Today remaining, and Now playing providers. `TakuWeather` (`org.taku.weather`) supplies
Weather and its source settings. Both are independent applications; the launcher is
only an AppWidgetHost. No provider reads Taku Feed state.
These packages have no launcher entries: Home lists only the actual Clock and Pixel
Weather apps. Android Settings identifies the provider packages as Taku Widgets and
Taku Weather Widget. Keep them installed to retain their widgets, cache and Agent APIs.

Build with `bash system/desktop-widgets/build.sh`. Outputs are ignored under `out/`.
Both APKs use the existing local development platform certificate and ordinary application
UIDs. `image/taku.mk` includes their source-built modules in development system images.
Install both before the launcher APK for the one-time default layout. Reinstalling keeps
configuration; never clear launcher data merely to migrate the desktop.

The Clock app reads system time, AlarmManager and active MediaSessions. Its RemoteViews
renders the clock digits in Space Grotesk; minute/time/alarm broadcasts and a non-wakeup
minute alarm refresh its content. The alarm also restarts the provider after process reclamation;
it does not wake a sleeping phone merely to redraw the clock. Day remaining is measured to the next local midnight (including timezone
and DST changes). Music uses the selected playing MediaSession and the same transport
controls exposed by its content provider. Media session access requires the platform-granted
MEDIA_CONTENT_CONTROL permission. It is not backed by sample track data.

Date taps open Calendar and Next alarm taps open the alarm application. Music taps open
the active player's session activity/launcher, or the default music application. Play
remains usable with no session and opens that music application. These foreground routes
share `ClockData.command` with the Agent API; commands requiring an activity return an
`agentCommand` instead of claiming a background launch succeeded. Unknown commands and
missing calendar/music/alarm handlers report explicit errors. Clock and Today remaining
open the installed alarm handler's main Clock application. They never open a custom
time/day status page. The legacy explicit Clock activity uses that same route but is
not advertised as a launcher entry.

Weather taps and its legacy explicit activity open **Pixel Weather**
(`com.google.android.apps.weather`). The separately installed Google APK is recorded in
`pixel-weather.lock.json`; it is not re-signed, and no NikGapps installer script or
privileged-permission XML is executed/imported. A missing/disabled Pixel Weather app
reports `PIXEL_WEATHER_UNAVAILABLE` through the Agent API and a visible message on tap;
it never falls back to the old simple weather screen. Pixel Weather and YouTube Music
are included in `image/google-apps.lock.json` and the default internal ROM product.
For a Google-enabled development emulator, `scripts/install-default-apps.py --serial
emulator-N` installs both apps, Taku Audio, and these widget providers in one step.

The widget's Open-Meteo source defaults to Guangzhou (23.1291, 113.2644). Its source settings and exported content provider
share an atomic SharedPreferences cache and city validation. A network job requests fresh
weather every 30 minutes when connected. Explicit refresh is asynchronous; failed requests
keep the last measurement with error/age information. City changes invalidate the old city's
cache and stale in-flight results cannot overwrite the new location. Data/attribution:
https://open-meteo.com/en/docs ; city search: https://open-meteo.com/en/docs/geocoding-api .

The public development endpoint needs no API key. A distribution using a different service
plan should configure its endpoint/credentials in the weather app, not in the launcher.

Pixel Weather keeps its own cities, units, forecasts and permissions. Its private data is
not exposed by this provider, and is not synchronized with the Open-Meteo widget cache.
The source-settings GUI remains explicitly available via
`am start -n org.taku.weather/.WeatherSettingsActivity`, with the same cache/validation
as `cities/search`, `location/set` and `refresh`; normal taps never open that page.

Authoritative on-device Agent commands, errors and data limitations are documented in
`system/assistant/assets/skills/taku-open-access/SKILL.md` (Standard Android App Widgets).

The visual system matches Figma 4-1 and the original Compose widgets: imported Space Grotesk
and Hanken Grotesk fonts, a constant 24dp card radius, 12dp content padding, 16dp grid gaps,
and original media assets. The host supplies the same wallpaper blur behind bundled
RemoteViews and reports the actual content size without subtracting default Android padding.
See `design-assets/README.md` for asset provenance and render geometry. Weather's compact
card always shows only a centered weather glyph: cached conditions retain their icon when
stale, and unavailable data uses a muted cloud placeholder. It never draws update times,
offline labels or placeholder text. City/temperature and stale/unavailable status remain
in its accessible content description, app and Agent data API.

## Widget click regression checks

On the assigned isolated emulator with the bundled widget layout, AOSP Calendar/DeskClock,
and no default music handler, build `bash system/desktop-widgets/tests/build.sh`, then run:

```sh
python3 scripts/test-home-widget-actions.py --serial emulator-N --output /tmp/widget-actions.json
```

The script clicks the current RemoteViews via `widgets/action`, verifies Calendar (or its
first-use Google account page), alarms, default music, the active session's dedicated player,
and Play/Pause. It also runs the Agent's returned music command. The temporary music fixture
declares a real MediaSession without capturing or playing audio; its session PendingIntent
deliberately does not opt into background launches, testing the host's foreground delegation.
The fixture is uninstalled in `finally`, and the original desktop layout is checked afterward.

Weather shares a per-process network budget across the GUI, widgets and Agent:
forecast starts are at least 30 seconds apart; extra requests coalesce into one
scheduled refresh using the latest saved location. `get` exposes `refreshScheduled`,
`refreshRetryAfterMs`, and `searchRetryAfterMs`; the GUI displays queued refreshes.
City searches have a 2-second interval and return `RATE_LIMITED` when repeated too
soon. These limits do not restrict the local Agent's right to read/change location.
The intentional open-access privacy/side-effect boundary is documented in
[Agent open access](../../docs/agent-open-access.md).

Run the clock-independent request-budget regression test with:

```sh
source scripts/android-env.sh
mkdir -p system/desktop-widgets/out/request-tests
"$JDK/bin/javac" -d system/desktop-widgets/out/request-tests system/desktop-widgets/weather/src/org/taku/weather/RequestInterval.java system/desktop-widgets/tests/RequestIntervalTest.java
"$JDK/bin/java" -cp system/desktop-widgets/out/request-tests org.taku.weather.RequestIntervalTest
```

# Pixel navigation integration — observation build

This build implements map querying, explicit global/local alignment and sensor-gated velocity proposals. **It does not execute real navigation.** The user requested development without moving the robot on 2026-11-04. No navigation start/execute endpoint is exposed, and `realActuationAllowed` is always false.

## Data flow

1. The in-app Taku AI proposes `map_search` / `map_route` actions. The validated assistant invokes a fixed loopback map service; it does not accept model-supplied URLs, credentials or arbitrary MCP tools.
2. `AMapMcp` negotiates Streamable HTTP, enumerates tools and calls official read-only AMap MCP tools. Search details supply coordinates. Ambiguous geocoding fails instead of choosing a place silently.
3. MCP walking directions currently omit polylines. The Web Service walking API independently supplies route geometry and a static overview. Both sources remain labelled; distance agreement is checked. Agreement does not prove identical paths or real-world traversability.
4. Foreground Android location updates retain provider, horizontal accuracy, mock status and monotonic age. A fresh, more accurate GPS fix is preferred over a later coarse network fix. Coordinates remain WGS84 until an explicit AMap conversion. The model receives readiness/quality, not phone coordinates.
5. Robot map location requires an explicit known GCJ-02 anchor or a colocated phone fix plus measured true heading, heading uncertainty and odometry-frame floor height. A phone fix by itself never becomes a robot fix. Phone colocation is a human assertion, not a sensor-verified fact.
6. `GeoReference` transforms routes into the odometry frame and propagates the anchored robot estimate. Reconnection, odometry resets and stale data invalidate it. Error growth is an assumption, not measured covariance or a guaranteed bound.
7. `NavigationPreview` combines route lookahead, odometry, a declared flat-floor LiDAR corridor and fresh camera person detections. It outputs body-frame forward speed and yaw rate, limited to 0.2 m/s and 0.25 rad/s. Missing floor, an obstacle, a person in the path, stale/skewed data, excessive estimated position uncertainty or route deviation produces zero proposals.

## Interface

- AI objects: `{"action":"map_search","keyword":"…","city":"…"}` and `{"action":"map_route","origin":"robot or explicit place","destination":"…","city":"…"}`.
- AI strings: `navigation_status`, `navigation_preview`, `show_navigator`.
- Mixing map/navigation actions with manual movement or `follow_start` is rejected. The existing manual and visual-follow features retain their own policies.
- `GET /api/navigator/state`: phone quality, anchored robot estimate, sensor freshness, route progress, proposals and blockers.
- `POST /api/navigator/phone-fix`: native foreground location sample; strict finite schema and sequence checks.
- `POST /api/navigator/calibrate`: surveyed GCJ-02 anchor, declared measurement uncertainty, true heading and floor Z.
- `POST /api/navigator/phone-anchor`: explicit colocation, fresh phone accuracy ≤5 m, WGS84→GCJ-02 conversion; reject if robot moves during conversion. A 3–5 m phone anchor can be displayed but is too uncertain for nonzero proposals (threshold 1 m).
- `POST /api/navigator/map`: validated MCP query; `origin=robot` resolves locally from a usable anchored estimate.
- `POST /api/navigator/prepare`: align the latest MCP/Web Service route; its start must be within 2 m of the local robot pose.
- `POST /api/navigator/sensors`: camera/LiDAR subscriptions only; no connect, gait switch, motion arm or stop command.
- `POST /api/navigator/observe`: bounded person boxes tied to a recently delivered camera frame key. Pixels stay on the phone.
- `POST /api/navigator/clear`: clear the anchor and transformed route.

The Android app and loopback HTTP routes remain a personal development surface, not a hardened multi-user robot-control service. Provider keys stay in private Termux storage, outside the APK and repository.

## What remains before autonomous real motion

- Obtain and measure a reliable robot global anchor or a continuous absolute-position source. No usable onboard Go2 Pro GNSS interface has been established. AMap searches/routes do not create robot coordinates.
- Validate the odometry frame, true heading, floor height and LiDAR geometry on the real robot. The flat-floor corridor is not stair/curb/cliff detection.
- Camera inference currently vetoes people only. It does not classify traffic lights, terrain, transparent obstacles or all road hazards; it does not locate the Pixel.
- Implement and validate an actuator-backed route execution session with foreground lease, manual takeover, bounded command segments, acknowledged stop and connection-loss recovery. The current proposal module deliberately has no actuator dependency.
- Add validated local detours/replanning. This build stops on hazards instead of claiming it can navigate around them.
- Conduct supervised direction, displacement, latency, emergency-stop and obstacle tests before a real-route trial. Software simulation cannot establish physical safety or accuracy.

## Verification

Run with the Go2 development Python environment:

```sh
python tests/go2-amap-mcp.test.py
python tests/go2-amap.test.py
python tests/go2-map-tools.test.py
python tests/go2-navigation-preview.test.py
python tests/go2-navigation-http.test.py
python tests/go2-assistant.test.py
python tests/go2-http.test.py
python tests/go2-geo-reference.test.py
node tests/go2-geo-projection.test.mjs
bash tooling/go2-lab/build.sh
```

The navigation test uses a synthetic kinematic plant for a 6 m right-angle route. It verifies turning, speed limits and arrival, plus obstacle/person/stale-frame/odometry-jump gates. It is not a live Go2 movement test. HTTP tests use an offline runtime and reject execution endpoints, invalid bodies and foreign origins.

On 2026-11-04: actual Pixel Taku AI → MCP → 404 m public walking route → app page completed. During an eight-sample passive robot check, odometry/camera/LiDAR were fresh and local person inference became fresh in seven samples; the robot had no calibrated map anchor, proposals stayed zero and no sport-motion request appeared in the new session counters. The phone reported coarse network location, not a new GPS fix. Detailed private evidence and rollback files are outside the repository in `taku-artifacts/go2-navigation-integration-20261104`.

## References

- [AMap MCP](https://developer.amap.com/api/mcp-server/gettingstarted)
- [AMap coordinate conversion](https://developer.amap.com/api/webservice/guide/api/convert)
- [Android location accuracy and monotonic age](https://developer.android.com/reference/android/location/Location)

Android's horizontal accuracy is a 68% confidence radius, not a maximum error guarantee. VPN/IP geolocation must not substitute for a GPS fix or a measured robot anchor.

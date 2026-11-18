---
name: cellular-network-country
description: Read and poll Android cellular network country through the God Mode org.taku.environment.country plugin.
---

Use existing God Mode tool calls. Do not start an independent observer or a second plugin host.

1. Discover tools with `taku-plugin godmode tools environment.country`; inspect arguments with `tool org.taku.environment.country country_read`.
2. `taku-plugin godmode call-tool org.taku.environment.country country_read '{}'` returns the cache. Pass `{"refresh":true}` to reread immediately.
3. `country_subscribe {}` returns `subscriptionId`, the current `snapshot`, and `ttlMs=600000`. Save the ID and use `country_poll {"subscriptionId":"RETURNED_ID"}` for nonblocking reads of new events.
4. Check `isError`. On success, read `structuredContent`, which matches the JSON in `content`. `dropped>0` means history has been lost; do not claim all events were received continuously.
5. Release your subscription with `country_unsubscribe {"subscriptionId":"RETURNED_ID"}`. Repeated calls return `removed=false`.

The country comes from TelephonyManager's current network country. `availability=UNKNOWN` or `countryIso=""` means unknown. Do not fill it with an old country, IP, GPS, or SIM home country. `stable=true` only means the SDK has observed the same country for at least 60 seconds; it does not prove physical location or travel.

Subscriptions expire after 10 minutes without a poll. Reloading, disabling, or recreating the provider invalidates old IDs. On `SUBSCRIPTION_INVALID`, resubscribe and handle the new snapshot; do not treat old events as a replay queue. A changed `providerGeneration` means a new process; `sequence` increases only within one generation. The plugin provides neither push delivery nor a persistent queue, and does not imply user consent to other actions.

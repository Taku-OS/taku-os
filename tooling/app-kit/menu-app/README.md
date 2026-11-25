# Fixed-template Menu App

This build runs on the prepared Android 16 development environment used by
the [native app kit](../README.md#java-native-apk-path). Stage this directory
at `/data/local/agentos/light-app-lab/menu-app` and the matching `build-native.sh`
at `/data/local/agentos/light-app-lab/build-native.sh`. Run from an Android shell
that can read the input and install packages for the active user.

```sh
sh /data/local/agentos/light-app-lab/menu-app/build.sh /path/to/menu.json
```

On success, stdout contains one line of the form
`taku-menu-app:org.agentos.generated.menu.m` followed by 32 lowercase hexadecimal
digits. The script checks that the package is installed and exposes the Chat
launch action before emitting this receipt. The app stays closed. Validation
and build failures exit nonzero and send diagnostics to stderr.

## Input

The top-level JSON object contains `venue`, `currency` and `dishes`.

- Venue provides string fields `name`, `address` and `placeId`. Use an empty
  string for an unknown field. The launcher label uses the venue name when available.
- Currency uses a three-letter ISO 4217 code. Each dish has a unique string
  `id`, a nonempty `name`, a nonnegative integer `priceMinor` in that currency's
  minor units, and an `ingredients` array of strings. Each of `kcal`, `carbs`,
  `fat` and `protein` can be a nonnegative number or null.
- A dish may include an `image` and any recommendations. For images, pass
  `images/<filename>.jpg`, `.jpeg`, `.png`, or `.webp` relative to the JSON
  file. Filenames accept letters, numbers, underscores and hyphens. The builder
  decodes and re-encodes supplied images before packaging. When `image` is
  omitted or empty, the builder uses the phone's local image gateway to generate
  a transparent illustration from the dish name and ingredients. It generates
  up to four illustrations concurrently and waits up to 120 seconds for the
  batch. Failed or timed-out illustrations leave the dish using its placeholder
  and log a diagnostic to stderr. The Chat cover uses the first supplied dish
  image, or the first successfully illustrated dish in menu order.
- Each recommendation provides `url`, `platform`, `author`, `title`,
  `restaurantPlaceId`, `dishId` and `verified: true`, with optional `thumbnail`,
  `type` and `excerpt`. Verify the post, venue and dish before adding a
  recommendation. The builder checks the identifiers and the HTTPS path to the
  original post. The generated app shows only posts that its corresponding
  Android social app can handle.

Every build packages the same local HTML, CSS and JavaScript with the validated
menu data and selected images. Each package gets its own local order and meal
history.

# Independent page observer example

This app observes its own page in its own process. Input or update taps send only an event ID, type, and increasing page revision to the Proactivity host; input text is not sent. The host displays a hint after changes settle. Tapping it returns a real acceptance callback to the plugin.

The app has its own package name, UID, and development signature. It needs no system permissions, host implementation dependencies, or private Java classes. Publishers can maintain their event sources and observer code in their own repositories.

## Build and modify

First generate the public JAR using the [host instructions](../../system/proactivity/README.md), or set `TAKU_PROACTIVITY_SDK=/absolute/path/taku-proactivity-client.jar` to a distributed SDK. The `build.sh` compilation classpath contains only the Android SDK and this JAR. It locates Android tools automatically inside this repository. After copying it to an external repository, set `ANDROID_SDK_ROOT`, `JAVA_HOME`, and `TAKU_PROACTIVITY_SDK` explicitly to use the same script without host source.

```sh
bash examples/proactivity-observer/build.sh 1
bash examples/proactivity-observer/build.sh 2
```

The settling delay is 700 ms in v1 and 1200 ms in v2, with different hint titles. Both default to at most 8 records, a 120-second lookback, and a 1500 ms hint interval for quick acceptance testing. These demonstration values are not recommended production frequencies. The build script generates the code version; v2 really has Android versionCode 2.

Tests or developers install explicitly; the script does not install. Keep ignored `out/signing/observer.jks` to upgrade an existing installation under the same developer identity. Local builds generate this key. Its public demonstration password is only for the fixture and must not be used for production releases. When copying this example to an external repository, replace the package name, observer ID, event type, and signature. Both IDs must belong to your own package namespace.

## Minimal SDK path

```java
client = new ProactivityClient(context, listener);
client.connect(); // Call register in onConnected.
client.register(new ObserverSpec(
    "org.example.taku.observer.page", "Page settling observer",
    "org.example.taku.observer.page_changed",
    700, 1500, 8, 120000, "Page settled", "Would you like to view it?"));
// After user approval, emit an event when something changes:
client.emit("org.example.taku.observer.page", "event-" + UUID.randomUUID(),
    "org.example.taku.observer.page_changed", pageRevision);
// When the page stops observing:
client.close();
```

`register` returns saved state, not proof of user approval. Check `approved/enabled/pendingRevision` and wait for user action inside the host. Repeated event IDs are deduplicated within the current bounded window. `published` means the display layer actually acknowledged publication; `accepted` means the user tapped, not that a task succeeded. See the [API](../../system/proactivity/API.md) for complete frames, errors, callbacks, and limits.

The example's debug section supports event retransmission and state inspection. The `probe` intent extra is only for testing host rejection of illegal operations, foreign namespaces, undeclared types, and a newer APK sending events without registration. `files/receipts.json` and page counters are local fixture data in the APK's private storage and are not committed to source.

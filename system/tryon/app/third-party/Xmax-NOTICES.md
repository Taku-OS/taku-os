# Xmax native transport notices

The Java X2.1 adapter implements the REST, session, room-event and external-video protocol documented by the official Xmax Android SDK 1.1.0 source release. It does not package the Kotlin/Compose Xmax AAR. Model is fixed to `x2.1`; Agora application ID is fixed to the official SDK constant. No server-returned application ID is trusted unless it matches that constant.

Protocol reference: https://github.com/Xmax-AI/XmaxSDK-Android
Published source: https://repo.maven.apache.org/maven2/ai/xmax/xmax-sdk/1.1.0/xmax-sdk-1.1.0-sources.jar

The packaged native dependency closure is `io.agora.rtc:full-rtc-basic:4.6.4` plus its sole POM dependency `io.agora.infra:aosl:1.3.5`. Both AAR hashes, sizes, exact Maven URLs and licenses are pinned in `dependencies.lock.json`. This adds Agora's bundled Java classes and native libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. All nested class JARs and JNI libraries are extracted by the offline dependency preparer; no AndroidX resources, Kotlin runtime, COS SDK, Gradle resolution, CDN or runtime dependency fetching is used. SDK log level is disabled before RTC engine creation; only an external video track is published, with camera and audio capture disabled.

COS upload uses bounded in-memory JPEG bytes, temporary credentials from `/cos/sts`, and Tencent COS v5 SHA1/HMAC request signing. The raw object-key path is signed and its URI encoding is used only for HTTPS. Upstream response bodies, room/user identifiers and credential strings are excluded from exceptions and diagnostic snapshots. Network requests reject redirects. Reference objects remain in the provider's object storage; stopping closes the generation session and revokes the temporary API key, it does not claim to delete uploaded reference objects.

Xmax SDK MIT license:

```text
MIT License

Copyright (c) 2026 XMAX.AI PTE. LTD.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Agora SDK license, as linked by the pinned Maven POMs (https://github.com/AgoraIO/full-sdk/blob/master/LICENSE):

```text
AGORA SDK License

Copyright (c) 2016-2021 agorabuilder. http://www.agora.io

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

# Go2 sources and asset notices

Original Go2 application code is contributed under the repository's Apache-2.0 license. Third-party material keeps its own terms. This import does not include an APK, Linux root filesystem, installed dependencies, private signing keys, account state or robot credentials.

| Material | Source and version | Terms / retained notice |
| --- | --- | --- |
| Go2 WebRTC driver, downloaded during setup | [unitree_webrtc_connect](https://github.com/legion1581/unitree_webrtc_connect/tree/e0abc5780761539eff89a382a79319e5cf6ad1f4) at `e0abc5780761539eff89a382a79319e5cf6ad1f4` | MIT, Konstantin Severov; `licenses/unitree-webrtc-connect-MIT.txt` |
| Vision runtime and pose model, downloaded during setup | `@mediapipe/tasks-vision` and the pose-landmarker-lite model, exact URLs/digests in `vision-assets.lock.json` | Apache-2.0; downloaded assets remain outside Git |
| Python dependencies | Exact versions in `requirements.txt` | Each upstream license applies; no wheels or native media libraries are committed |
| `hello.wav`, `goodbye.wav` | Sjors Provoost and Wyang, respectively, via Wikimedia Commons; source URLs, hashes and transformations in `audio-presets/catalog.json` | CC BY-SA 3.0, retained in `licenses/CC-BY-SA-3.0.txt` |
| `thanks.wav` | Wei Gao, Vion Nicolas / Shtooka Project, via Wikimedia Commons | CC BY 2.0 FR, retained in `licenses/CC-BY-2.0-FR.txt` |
| `dog-bark.wav` | felix.blume, [Freesound 199261](https://freesound.org/people/felix.blume/sounds/199261/) | CC0 1.0, retained in `licenses/CC0-1.0.txt` |
| `confirm.wav`, `bell.wav` | [Kenney interface sounds](https://opengameart.org/content/interface-sounds) | CC0; original `audio-presets/KENNEY-LICENSE.txt` retained |
| 21 English `en-*.wav` files | Original scripted lines and generated voice; Qwen3-TTS VoiceDesign, recipe and per-file provenance in `audio-presets/` | Model: Apache-2.0. The model license does not relicense unrelated recordings. No celebrity voice reference is included |
| `web/robot.png`, `web/wallpaper.png` | Team-supplied [Taku Mobile OS design, Robot panel 338:4118](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=338-4118), used in the installed app | Design provenance retained. No ownership claim over Unitree trademarks or depicted product design; asset rights must be retained in downstream distribution |

The audio catalog is authoritative for clip authors, source pages, licenses, changes and SHA-256 values. The UI exposes those attributions. Scenario speech is prerecorded content and does not prove that its described action occurred. Historical investigation reports are dated evidence, not current upstream compatibility guarantees.

The deterministic gray frame in `tests/helpers/go2-image-fixture.cjs` is generated test data; it contains no person or camera recording. The worker in those tests supplies synthetic landmarks, so the tests establish UI/control behavior, not person-detection accuracy.

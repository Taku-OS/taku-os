# Compact native-capability UI · 2026-10-15

v45 (20261015-native-compact) is deployed on the physical Pixel 9. It references the accepted compact Skills / Harness lists from the Codex conversation “Android image”, reusing DrawerTheme, icon assets, and drawer interactions.

## Evidenced UI issues and changes

1. Capabilities home: the old UI repeated the title/back controls at the top and nested multiple large outlined buttons inside cards. Device screenshot before.png shows this occupying much of the initial screen. New native.png uses 7 grouped navigation entries, all visible together; there are 0 large in-page buttons, and back navigation is unified at the upper left.
2. Models page: the old UI displayed a separate button for every model and reasoning effort. New models.png shows the runtime default and all 7 models on one screen; reasoning effort uses one selector, and multi-agent policy opens for selection. Model/effort selection still sends through existing native configuration without changing an executing turn.
3. Settings page: the old UI simultaneously expanded account descriptions, theme buttons, developer buttons, title input, and style buttons. New settings.png groups account, assistant, preferences, and screenshot input. Title editing moves into a dialog opened on demand; themes and response styles are selected as needed.
4. Connectors, conversations, and workspaces: large-button directories become names, summaries, icons, and detail entries; the tool catalog supports search and shows invocation actions only inside tool details. Native interfaces, remote access, memory, and events remain in the top-bar menu. Windows-only methods remain visible but disabled.
5. Harness / Skills: compact components are synchronized from the other conversation; modules retain expanded presentation. Community downloads on the phone use the same private proxy setting as native workers, preventing the root backend from bypassing phone VpnService application routing. The catalog actually returned 39 OpenAI Skills.

## Acceptance

Display regression found that explicitly opening another conversation could retain the settings page or place a new reply outside the old scroll position. Each system invocation now returns to the conversation; new messages are positioned after layout, while manual scrolling preserves the reading position. Native questions take priority by showing their question cards.

- Physical Pixel 9: all 7 capability entries visible; 7 models and the default option on the same screen.
- Model/reasoning-effort selection, back/more menus, dark/light themes, and opening/canceling the title dialog passed; the model default and dark theme were restored.
- Real dynamic tools, same-turn steer, and question-card responses passed regression on the final component version (39.739 seconds); desktop Hi auto-expansion and continued conversation passed (13.476 seconds). Full record: evidence/builds/2026-10-15-native-codex-ui.json.
- Skills and Harness entry/return navigation passed; the current physical-device conversation showed all 7 Harness modules on one screen.
- Native interface registration remains 168 real methods, excluding mock. Underlying-service availability retains the explicit boundaries in [native client acceptance](native-codex-implementation-2026-10-15.md); UI rendering does not imply acceptance of every business service.

Touch targets are at least 44dp; screenshots and control trees support these layout conclusions. TalkBack, enlarged fonts, multiple small-screen sizes, and full accessibility compliance have not been accepted.

## Delivery

- [Physical-device screenshots](reviews/pixel9-native-compact-2026-10-15.html).
- Screenshots and actual UI inspection records: out/native-design-2026-10-15/.
- Remote screenshots viewable on the phone are protected by the existing Workspace Portal account; existing Skills/Harness reports are unchanged.
- This update replaces the assistant APK and necessary broker components, retaining accounts, history, and private network configuration without reflashing the complete ROM.

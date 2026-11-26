# The current root runtime uses the upstream userdebug su domain.
# Product discovery also parses eng configurations. Install these only in debug
# images; the build entry additionally requires userdebug after lunch selection.

$(call inherit-product, vendor/taku/image/product-branding.mk)
TAKU_INCLUDE_GOOGLE_APPS ?= true
ifeq ($(TAKU_INCLUDE_GOOGLE_APPS),true)
$(call inherit-product, vendor/taku/image/google-services.mk)
ifneq ($(wildcard vendor/taku/image/google-apps.mk),)
$(call inherit-product, vendor/taku/image/google-apps.mk)
endif
endif

# Opt in only for a personal Pixel image. No subscription or account data ships.
# Upstream 0.9.0 explicitly disallows commercial bundled sales in its app statement.
ifeq ($(TAKU_PREINSTALL_PERSONAL_PROXY),true)
PRODUCT_PACKAGES_DEBUG += TakuPersonalProxy
endif

PRODUCT_PACKAGES_DEBUG += \
    TakuSurfaces \
    TakuArrival \
    TakuMemory \
    TakuInputMethod \
    TakuAssistant \
    TakuAgent \
    TakuClock \
    TakuWeather \
    TakuAudioEngine \
    agentos-task-broker \
    taku-godmode-plugin-sdk \
    taku-godmode-host \
    taku-godmode-host-launcher \
    taku-adb \
    taku-rea-pack \
    taku-rea-provision \
    taku-rea-pack-config \
    TakuGodMode \
    agentos-virtual-display \
    agentos-resolver-bridge \
    taku-device \
    taku-device-probe \
    phone-display \
    taku-runtime-start \
    taku-codex-launcher \
    taku-plugin \
    taku-magpie-launcher \
    taku-magpie-runtime \
    taku-codex-license \
    taku-codex-notice \
    taku-magpie-license \
    taku-task-result-schema \
    taku-codex-runtime-archive \
    taku-code-mode-runtime-archive \
    taku-runtime-ca-bundle

ifneq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
PRODUCT_SYSTEM_EXT_PROPERTIES += ro.taku.image.channel=development
PRODUCT_SYSTEM_EXT_PROPERTIES += ro.taku.home.package=org.agentos.agent
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/taku/image/home/sepolicy
TAKU_ENABLE_PIXEL_OTA ?= true
ifeq ($(TAKU_ENABLE_PIXEL_OTA),true)
PRODUCT_SYSTEM_EXT_PROPERTIES += lineage.updater.uri=https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}
endif

PRODUCT_COPY_FILES += \
    vendor/taku/image/runtime/resolv.conf:$(TARGET_COPY_OUT_SYSTEM)/etc/resolv.conf
PRODUCT_COPY_FILES += $(call find-copy-subdir-files,*,vendor/taku/system/plugins/rea-provider,$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/taku/rea-provider)
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += system/etc/resolv.conf
endif

# Pixel Launcher/Gemini are absent from the selected Lineage package set.
# Keep the upstream recents provider until Taku implements the Quickstep contract.

# Gesture navigation (Figma 1-6): one horizontal handle instead of back/home/recents.
# OverlayManager enables this default overlay at boot only while no navigation-bar overlay
# is enabled in its category, so a mode the user picks in Settings is kept. Swipe-up
# home/recents uses the upstream recents provider kept above.
PRODUCT_PACKAGES += NavigationBarModeGesturalOverlay
PRODUCT_PRODUCT_PROPERTIES += ro.boot.vendor.overlay.theme=com.android.internal.systemui.navbar.gestural

# Assistant gesture: double tap on the handle (image/launcher/, applied by
# apply-launcher-patches.py) replaces the bottom-corner swipe, whose default this
# overlay turns off. Settings.Secure.assist_touch_gesture_enabled=1 still restores it.
PRODUCT_PACKAGES += TakuGestureOverlay

# Taku lock screen (docs/lock-screen.md). The SystemUI code is applied by
# apply-lockscreen-patches.py; these overlays set the default shortcuts (Flashlight,
# Camera), their geometry, and keep face unlock on the lock screen. The design's fonts
# and default lock wallpaper are product files; WallpaperManager reads the default
# lock wallpaper from ro.config.lock_wallpaper until the user picks another.
PRODUCT_PACKAGES += TakuLockscreenSystemUIOverlay TakuLockscreenFrameworkOverlay
PRODUCT_COPY_FILES += \
    vendor/taku/system/assistant/res/font/space_grotesk_regular.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/taku/SpaceGrotesk-Regular.ttf \
    vendor/taku/system/assistant/res/font/hanken_grotesk_regular.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/taku/HankenGrotesk-Regular.ttf \
    vendor/taku/system/assistant/res/font/hanken_grotesk_medium.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/taku/HankenGrotesk-Medium.ttf \
    vendor/taku/system/assistant/design-system/licenses/SpaceGrotesk-OFL.txt:$(TARGET_COPY_OUT_PRODUCT)/fonts/taku/SpaceGrotesk-OFL.txt \
    vendor/taku/system/assistant/design-system/licenses/HankenGrotesk-OFL.txt:$(TARGET_COPY_OUT_PRODUCT)/fonts/taku/HankenGrotesk-OFL.txt \
    vendor/taku/system/lockscreen/media/default_lock_wallpaper.jpg:$(TARGET_COPY_OUT_PRODUCT)/media/taku/lockscreen/default_lock_wallpaper.jpg
PRODUCT_PRODUCT_PROPERTIES += ro.config.lock_wallpaper=/product/media/taku/lockscreen/default_lock_wallpaper.jpg

# Locked, read-only ASR/TTS resources; no first-boot download or data seeding.
include vendor/taku/system/audio-engine/models/rom-files.mk

# Same pinned Lineage framework and Taku runtime, with Goldfish ARM64 hardware.
# Keep the same default apps/runtime as Pixel unless clean open-source mode overrides it.
-include vendor/taku/image/emulator/build-options.mk
TAKU_INCLUDE_GOOGLE_APPS ?= true
TAKU_ENABLE_PIXEL_OTA := false
WITH_ADB_INSECURE := true
TARGET_NO_KERNEL_OVERRIDE := true
TARGET_NO_RECOVERY := true
TARGET_SCREEN_WIDTH := 1080
TARGET_SCREEN_HEIGHT := 2424
BOARD_EMULATOR_DYNAMIC_PARTITIONS_SIZE := 6442450944

$(call inherit-product, device/generic/goldfish/64bitonly/product/sdk_phone64_arm64.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)
$(call inherit-product, vendor/taku/image/taku.mk)

PRODUCT_NAME := lineage_taku_emu_arm64
PRODUCT_DEVICE := taku_emu64a
PRODUCT_BRAND := Taku
PRODUCT_MODEL := Taku Lineage Island Development
PRODUCT_MANUFACTURER := Taku
PRODUCT_SYSTEM_EXT_PROPERTIES += ro.taku.image.target=emulator

# A developer image must never consume the Pixel OTA feed.
PRODUCT_SYSTEM_EXT_PROPERTIES += lineage.updater.uri=http://127.0.0.1:9/emulator-no-ota

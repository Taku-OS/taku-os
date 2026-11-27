_taku_device_debug_packages :=
_taku_device_system_ext_properties :=

# Opt in only for a personal Pixel image. No subscription or account data ships.
# Upstream 0.9.0 explicitly disallows commercial bundled sales in its app statement.
ifeq ($(TAKU_PREINSTALL_PERSONAL_PROXY),true)
_taku_device_debug_packages := TakuPersonalProxy
endif

ifneq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
TAKU_ENABLE_PIXEL_OTA ?= true
ifeq ($(TAKU_ENABLE_PIXEL_OTA),true)
_taku_device_system_ext_properties := lineage.updater.uri=https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}
endif
endif

# Include in the existing product node to preserve Android inheritance ordering.
include vendor/taku/image/taku-core.mk
_taku_device_debug_packages :=
_taku_device_system_ext_properties :=

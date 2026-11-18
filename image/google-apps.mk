# Proprietary original APKs; this dependency is restricted to internal testing.
PRODUCT_SOONG_NAMESPACES += vendor/taku-google-apps
PRODUCT_PRODUCT_PROPERTIES += ro.taku.image.distribution=internal-test-only
$(call inherit-product, vendor/taku-google-apps/modules.mk)

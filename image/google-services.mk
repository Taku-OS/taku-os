# The native ARM64 dependency is pinned and mounted read-only by the builder.
# Default for new Taku images; missing dependencies must fail, never silently omit GMS.
$(call inherit-product, vendor/gapps/arm64/arm64-vendor.mk)
PRODUCT_PACKAGES += TakuGoogleAssistantOverlay

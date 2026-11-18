include device/generic/goldfish/board/emu64a/BoardConfig.mk
include vendor/lineage/config/BoardConfigLineage.mk

# The Goldfish prebuilt kernel/modules belong to the pinned platform manifest.
TARGET_NO_KERNEL_OVERRIDE := true
TARGET_NO_RECOVERY := true

# System settings, brightness and Do Not Disturb · Research proposal / not yet tested
# Environment: Root shell on the phone
# Prerequisites: Ordinary apps have specific settings-write grants; shell can call settings; prefer the owning service over direct database writes
# Expected result: Read brightness as a probe without side effects; changing it requires setting a value and observing the effect
# This review did not automatically execute this example; define scenario-specific success criteria first.
settings get system screen_brightness

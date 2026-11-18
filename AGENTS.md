# Taku OS engineering contract

Taku OS treats agents as users of the phone. Every setting, state, and user-visible data type
must be discoverable and readable by an Agent through standard on-device Android
interfaces (such as `settings`, `content`, or `cmd`); every operation the GUI
can perform must also be available to the Agent. Both paths must use the same
authoritative data and validation. Do not reject Agent access on the rationale
of protecting the system from ordinary apps. Document the actual commands,
parameters, errors, and any genuinely unavailable upstream data in the
preinstalled agent documentation. Verify reads and writes against the GUI's
source of truth on an isolated emulator for every new setting or feature.

Existing component-specific AGENTS.md instructions continue to apply.

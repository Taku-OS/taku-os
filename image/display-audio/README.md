# Dedicated display audio

The pinned frameworks/base patch applies in the phone and emulator image builders.
WindowManager recognizes trusted, separately grouped virtual displays with the
Taku host's reserved name and a privileged owner UID. It publishes app UIDs
from non-finishing activities on those displays and excludes any UID with a
visible activity on a regular display. A display removal also refreshes the
snapshot, including removal after the display owner's Binder dies.

PlaybackActivityMonitor applies its own zero-gain VolumeShaper to new and
existing players and reverses it when the UID leaves the snapshot. Playback
continues to use its normal device and clock. New media and game focus requests
from muted UIDs receive a grant without entering the default focus stack.

Playback attribution is per UID. Concurrent visible regular-display activity
for the same UID takes precedence. Streams that do not implement the framework
player's VolumeShaper interface, including AAudio and SoundPool, are outside
this implementation's mute coverage.

`python3 tests/display-audio-patch.test.py` verifies the pinned upstream input
hashes, checks both patch directions, and runs the ownership policy on the host.

// Research: network state, VPN and per-app networking · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Obtain consent after VpnService.prepare; establish tun and forward traffic correctly; concurrent configurations remain limited by the system
// Expected result: Shows VPN authorization; without a tun interface this does not establish traffic proxying
// This review did not automatically execute this example; define scenario-specific success criteria first.
val consent = VpnService.prepare(this)
if (consent != null) startActivityForResult(consent, 103)

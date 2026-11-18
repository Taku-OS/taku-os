// Bluetooth / local-network device discovery · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Use NSD to discover a test service on an authorized LAN; pairing/connection need separate authorization; discovery does not mean connected
// Expected result: Discovers your published test service; implement the listener and stopDiscovery
// This review did not automatically execute this example; define scenario-specific success criteria first.
getSystemService(NsdManager::class.java).discoverServices("_taku._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)

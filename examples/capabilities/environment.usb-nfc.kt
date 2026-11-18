// Research: USB peripherals and NFC / HCE · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Check FEATURE_USB_HOST/FEATURE_NFC first; USB requestPermission names the target device; use test peripherals only
// Expected result: Enumerates peripherals; does not establish connection or protocol read/write success
// This review did not automatically execute this example; define scenario-specific success criteria first.
val devices = getSystemService(UsbManager::class.java).deviceList
Log.i("TakuProbe", "usbCount=${devices.size}")

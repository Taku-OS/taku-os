// Runs the actual generated Updater helper, not a second version parser.
package org.lineageos.updater

import android.os.SystemProperties

fun main(args: Array<String>) {
    val expectedVersion = args.single()
    check(TakuBrand.NAME == "TakuOS")
    check(TakuBrand.VERSION == expectedVersion)
    for (version in listOf("1.0", "1.0.0", "1.0.1", "1.9.0", "1.10.0", "2.0.0")) {
        check(TakuBrand.targetVersion("TakuOS$version") == version)
        check(TakuBrand.targetDisplayName("TakuOS$version") == "TakuOS $version")
        check(TakuBrand.buildLabel("TakuOS$version", "Oct 26") == "TakuOS $version - Oct 26")
    }
    for (value in listOf(null, "", "1.0.1", "OtherOS1.0.1", "TakuOS 1.0.1", "TakuOS1.0.01",
                         "TakuOS01.0.1", "TakuOS1.0.1.2", "TakuOS1.0.1-beta.1", "TakuOS1.0.1\n")) {
        check(TakuBrand.targetVersion(value) == null)
        check(TakuBrand.targetDisplayName(value) == "TakuOS")
    }
    check(TakuBrand.currentVersion == expectedVersion)
    SystemProperties.values["ro.taku.product.version"] = "1.0"
    check(TakuBrand.currentVersion == "1.0")
    SystemProperties.values["ro.taku.product.version"] = "1.10.0"
    SystemProperties.values["ro.taku.display.version"] = "TakuOS1.0"
    check(TakuBrand.currentVersion == "1.10.0")
    SystemProperties.values["ro.taku.product.version"] = "invalid"
    check(TakuBrand.currentVersion == "1.0")
    SystemProperties.values["ro.taku.display.version"] = "invalid"
    check(TakuBrand.currentVersion == expectedVersion)
    println("Generated TakuBrand runtime checks passed")
}

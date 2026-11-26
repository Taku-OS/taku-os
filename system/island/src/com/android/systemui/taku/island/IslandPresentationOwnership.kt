/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every actually drawn island (primary or waiting disc) owns its upstream chip; hidden ones do not. */
object IslandPresentationOwnership {
    data class Owner(val notificationKeys: Set<String> = emptySet(), val sources: Set<String> = emptySet())
    private val mutable = MutableStateFlow(Owner())
    val state = mutable.asStateFlow()
    fun show(primary: IslandActivity?, secondary: IslandActivity? = null) {
        val visible = listOfNotNull(primary, secondary)
        mutable.value = Owner(
            visible.filterNot { it.key.startsWith("taku:system:") }.map { it.key }.toSet(),
            visible.filter { it.key.startsWith("taku:system:") }
                .map { it.payload.optString("systemSource") }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}

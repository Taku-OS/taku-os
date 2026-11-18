/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the actually displayed primary island owns its upstream chip. Hidden islands own nothing. */
object IslandPresentationOwnership {
    data class Owner(val notificationKey: String? = null, val source: String? = null)
    private val mutable = MutableStateFlow(Owner())
    val state = mutable.asStateFlow()
    fun show(activity: IslandActivity?) {
        mutable.value = when {
            activity == null -> Owner()
            activity.key.startsWith("taku:system:") -> Owner(source = activity.payload.optString("source"))
            else -> Owner(notificationKey = activity.key)
        }
    }
}

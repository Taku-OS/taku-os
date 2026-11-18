/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface IslandModule {
    @Binds
    @IntoMap
    @ClassKey(IslandStartable::class)
    fun bindStartable(impl: IslandStartable): CoreStartable
}

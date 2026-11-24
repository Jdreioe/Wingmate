package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.di.sharedModules
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/**
 * Starts the shared Koin graph with all feature modules from [sharedModules]
 * followed by any platform modules (Android or iOS) that override the
 * in-memory/no-op defaults.
 */
fun initKoin(vararg platformModules: Module) {
    startKoin {
        allowOverride(true)
        modules(sharedModules() + platformModules)
    }
}

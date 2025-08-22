package io.github.jdreioe.wingmate.util

import com.arkivanov.mvikotlin.core.store.Store
import kotlinx.coroutines.flow.Flow
import com.arkivanov.mvikotlin.extensions.coroutines.states as flowStates

fun <T : Any> Store<*, T, *>.asFlow(): Flow<T> =
    flowStates

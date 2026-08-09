package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.ConfigRepository

/** Compatibility name retained for callers; storage is Keystore-backed. */
class AndroidConfigRepository(context: Context) : ConfigRepository by AndroidSqlConfigRepository(context)

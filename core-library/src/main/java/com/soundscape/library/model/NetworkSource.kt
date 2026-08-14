package com.soundscape.library.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-attached network library root: a NAS share, WebDAV endpoint,
 * or self-hosted media server. Credentials are NOT stored here — this
 * row only holds connection metadata; the credential itself lives in
 * Android Keystore-backed EncryptedSharedPreferences, keyed by [id].
 */
@Entity(tableName = "network_sources")
data class NetworkSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val displayName: String,
    val type: SourceType,
    val host: String,
    val port: Int?,
    val basePath: String,          // share/export/root path, e.g. "/music"
    val username: String?,         // credential itself is NOT stored here

    val cacheLocally: Boolean = false,
    val lastSyncedAt: Long? = null,
    val enabled: Boolean = true
)

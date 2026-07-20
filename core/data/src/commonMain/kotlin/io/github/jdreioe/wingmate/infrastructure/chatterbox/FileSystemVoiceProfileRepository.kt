package io.github.jdreioe.wingmate.infrastructure.chatterbox

import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.chatterbox.ClonedVoiceProfile
import io.github.jdreioe.wingmate.domain.chatterbox.VoiceProfileRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class FileSystemVoiceProfileRepository(
    private val fileStorage: FileStorage,
) : VoiceProfileRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(): List<ClonedVoiceProfile> {
        val data = fileStorage.load(INDEX_FILE) ?: return emptyList()
        return try {
            json.decodeFromString<List<ClonedVoiceProfile>>(data)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun get(id: String): ClonedVoiceProfile? {
        return list().find { it.id == id }
    }

    override suspend fun save(profile: ClonedVoiceProfile) {
        val profiles = list().toMutableList()
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        fileStorage.save(INDEX_FILE, json.encodeToString(profiles))
    }

    override suspend fun delete(id: String) {
        val profiles = list().toMutableList()
        profiles.removeAll { it.id == id }
        fileStorage.save(INDEX_FILE, json.encodeToString(profiles))
        fileStorage.delete("$VOICES_DIR/$id")
        val active = fileStorage.load(ACTIVE_FILE)
        if (active == id) fileStorage.delete(ACTIVE_FILE)
    }

    override suspend fun getActive(): ClonedVoiceProfile? {
        val activeId = fileStorage.load(ACTIVE_FILE) ?: return null
        return get(activeId.trim())
    }

    override suspend fun setActive(profile: ClonedVoiceProfile) {
        fileStorage.save(ACTIVE_FILE, profile.id)
    }

    override suspend fun clearActive() {
        fileStorage.delete(ACTIVE_FILE)
    }

    companion object {
        private const val VOICES_DIR = "voices"
        private const val INDEX_FILE = "$VOICES_DIR/index.json"
        private const val ACTIVE_FILE = "$VOICES_DIR/active.txt"
    }
}

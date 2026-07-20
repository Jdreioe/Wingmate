package io.github.jdreioe.wingmate.domain.chatterbox

interface VoiceProfileRepository {
    suspend fun list(): List<ClonedVoiceProfile>
    suspend fun get(id: String): ClonedVoiceProfile?
    suspend fun save(profile: ClonedVoiceProfile)
    suspend fun delete(id: String)
    suspend fun getActive(): ClonedVoiceProfile?
    suspend fun setActive(profile: ClonedVoiceProfile)
    suspend fun clearActive()
}

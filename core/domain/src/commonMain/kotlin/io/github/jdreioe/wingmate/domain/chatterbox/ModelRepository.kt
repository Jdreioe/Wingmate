package io.github.jdreioe.wingmate.domain.chatterbox

interface ModelRepository {
    suspend fun list(): List<ChatterboxModel>
    suspend fun get(id: String): ChatterboxModel?
    suspend fun save(model: ChatterboxModel)
    suspend fun delete(id: String)
    suspend fun getActive(): ChatterboxModel?
    suspend fun setActive(model: ChatterboxModel)
}
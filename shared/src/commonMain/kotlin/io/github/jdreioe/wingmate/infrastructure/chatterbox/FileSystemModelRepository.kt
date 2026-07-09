package io.github.jdreioe.wingmate.infrastructure.chatterbox

import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxModel
import io.github.jdreioe.wingmate.domain.chatterbox.ModelRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class FileSystemModelRepository(
    private val fileStorage: FileStorage,
) : ModelRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(): List<ChatterboxModel> {
        val data = fileStorage.load(INDEX_FILE) ?: return emptyList()
        return try {
            json.decodeFromString<List<ChatterboxModel>>(data)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun get(id: String): ChatterboxModel? {
        return list().find { it.id == id }
    }

    override suspend fun save(model: ChatterboxModel) {
        val models = list().toMutableList()
        val idx = models.indexOfFirst { it.id == model.id }
        if (idx >= 0) models[idx] = model else models.add(model)
        fileStorage.save(INDEX_FILE, json.encodeToString(models))
    }

    override suspend fun delete(id: String) {
        val models = list().toMutableList()
        models.removeAll { it.id == id }
        fileStorage.save(INDEX_FILE, json.encodeToString(models))
        fileStorage.delete("$MODELS_DIR/$id")
        val active = fileStorage.load(ACTIVE_FILE)
        if (active == id) fileStorage.delete(ACTIVE_FILE)
    }

    override suspend fun getActive(): ChatterboxModel? {
        val activeId = fileStorage.load(ACTIVE_FILE) ?: return null
        return get(activeId.trim())
    }

    override suspend fun setActive(model: ChatterboxModel) {
        fileStorage.save(ACTIVE_FILE, model.id)
    }

    companion object {
        private const val MODELS_DIR = "models"
        private const val INDEX_FILE = "$MODELS_DIR/index.json"
        private const val ACTIVE_FILE = "$MODELS_DIR/active.txt"
    }
}
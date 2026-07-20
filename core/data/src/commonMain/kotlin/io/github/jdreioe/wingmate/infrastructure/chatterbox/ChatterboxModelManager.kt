package io.github.jdreioe.wingmate.infrastructure.chatterbox

import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxModel
import io.github.jdreioe.wingmate.domain.chatterbox.ModelRepository
import io.github.jdreioe.wingmate.domain.chatterbox.ModelSource

class ChatterboxModelManager(
    private val modelRepository: ModelRepository,
) {
    fun listOfficial(): List<ChatterboxModel> {
        return OfficialModelRegistry.models
    }

    fun listCommunity(): List<ChatterboxModel> = emptyList()

    suspend fun listInstalled(): List<ChatterboxModel> {
        return modelRepository.list().filter { it.isInstalled }
    }

    suspend fun getActive(): ChatterboxModel? {
        return modelRepository.getActive()
    }

    suspend fun setActive(model: ChatterboxModel) {
        modelRepository.setActive(model)
    }

    suspend fun markInstalled(model: ChatterboxModel, storagePath: String): ChatterboxModel {
        val installed = model.copy(isInstalled = true, storagePath = storagePath)
        modelRepository.save(installed)
        return installed
    }

    suspend fun delete(model: ChatterboxModel) {
        modelRepository.delete(model.id)
    }

    suspend fun refreshInstallationStatus(): List<ChatterboxModel> {
        val allModels = listOfficial() + listCommunity()
        val installed = modelRepository.list()
        val installedIds = installed.map { it.id }.toSet()
        val merged = allModels.map { official ->
            val existing = installed.find { it.id == official.id }
            existing ?: official
        }
        for (model in installed) {
            if (model.id !in installedIds) {
                merged + model
            }
        }
        for (model in merged) {
            modelRepository.save(model)
        }
        return merged
    }
}

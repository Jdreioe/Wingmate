package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.CategoryRepository

@Deprecated("Prefer CategoryUseCase; retained for legacy call sites")
class DeleteCategoryUseCase(
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(categoryId: String) {
        categoryRepository.delete(categoryId)
    }
}

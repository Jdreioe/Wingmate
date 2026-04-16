package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository

@Deprecated("Prefer CategoryUseCase; retained for legacy call sites")
class AddCategoryUseCase(
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(category: CategoryItem): CategoryItem {
        return categoryRepository.add(category)
    }
}

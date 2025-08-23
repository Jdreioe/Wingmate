package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import kotlinx.datetime.Clock

class AddCategoryUseCase(private val categoryRepository: CategoryRepository) {
    suspend operator fun invoke(name: String): CategoryItem {
        if (name.isBlank()) {
            throw IllegalArgumentException("Category name cannot be blank")
        }
        val category = CategoryItem(
            id = "", // Handled by repository
            name = name,
            isFolder = false,
            selectedLanguage = null
        )
        return categoryRepository.add(category)
    }
}

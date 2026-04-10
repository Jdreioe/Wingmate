package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.CategoryRepository

class MoveCategoryUseCase(private val categoryRepo: CategoryRepository) {
    suspend operator fun invoke(fromIndex: Int, toIndex: Int) {
        categoryRepo.move(fromIndex, toIndex)
    }
}

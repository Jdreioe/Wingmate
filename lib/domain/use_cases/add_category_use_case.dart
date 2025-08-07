import 'package:wingmate/domain/entities/category.dart';
import 'package:wingmate/domain/repositories/category_repository.dart';
import 'package:wingmate/infrastructure/models/category_item.dart';

class AddCategoryUseCase {
  final CategoryRepository categoryRepository;

  AddCategoryUseCase({
    required this.categoryRepository,
  });

  Future<void> execute(String categoryName, String language) async {
    final newCategory = Category(
      name: categoryName,
      language: language,
    );
    await categoryRepository.addCategory(newCategory);
  }
}

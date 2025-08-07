import 'package:wingmate/domain/entities/category.dart';

abstract class CategoryRepository {
  Future<List<Category>> getCategories();
  Future<Category?> getCategoryById(int id);
  Future<void> saveCategory(Category category);
  Future<void> deleteCategory(int id);
  Future<void> addCategory(Category category);
}

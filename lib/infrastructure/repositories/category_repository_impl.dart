import 'package:wingmate/domain/entities/category.dart';
import 'package:wingmate/domain/repositories/category_repository.dart';
import 'package:wingmate/infrastructure/data_sources/category_data_source.dart';

class CategoryRepositoryImpl implements CategoryRepository {
  final CategoryLocalDataSource localDataSource;

  CategoryRepositoryImpl(this.localDataSource);

  @override
  Future<List<Category>> getCategories() async {
    return await localDataSource.getCategories();
  }

  @override
  Future<Category?> getCategoryById(int id) async {
    return await localDataSource.getCategoryById(id);
  }

  @override
  Future<void> saveCategory(Category category) async {
    return await localDataSource.saveCategory(category);
  }

  @override
  Future<void> deleteCategory(int id) async {
    return await localDataSource.deleteCategory(id);
  }
}

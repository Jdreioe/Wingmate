import 'package:wingmate/domain/entities/category.dart';
import 'package:wingmate/infrastructure/data/category_dao.dart';
import 'package:wingmate/infrastructure/models/category_item.dart';

abstract class CategoryDataSource {
  Future<List<Category>> getCategories();
  Future<Category?> getCategoryById(int id);
  Future<void> saveCategory(Category category);
  Future<void> deleteCategory(int id);
}

class CategoryLocalDataSource implements CategoryDataSource {
  final CategoryDao categoryDao;

  CategoryLocalDataSource(this.categoryDao);

  @override
  Future<List<Category>> getCategories() async {
    final categoryItems = await categoryDao.getAll();
    return categoryItems.map((item) => item.toDomain()).toList();
  }

  @override
  Future<Category?> getCategoryById(int id) async {
    final categoryItem = await categoryDao.getById(id);
    return categoryItem?.toDomain();
  }

  @override
  Future<void> saveCategory(Category category) async {
    final categoryItem = CategoryItem.fromDomain(category);
    if (category.id == null) {
      await categoryDao.insert(categoryItem);
    } else {
      await categoryDao.update(categoryItem);
    }
  }

  @override
  Future<void> deleteCategory(int id) async {
    await categoryDao.delete(id);
  }
}

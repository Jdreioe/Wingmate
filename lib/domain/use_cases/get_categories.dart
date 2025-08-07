import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/entities/category.dart';
import 'package:wingmate/domain/repositories/category_repository.dart';

class GetCategoriesUseCase implements UseCase<List<Category>, NoParams> {
  final CategoryRepository repository;

  GetCategoriesUseCase(this.repository);

  @override
  Future<List<Category>> call(NoParams params) async {
    return await repository.getCategories();
  }
}

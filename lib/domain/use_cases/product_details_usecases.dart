import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/entities/product_details.dart';
import 'package:wingmate/domain/repositories/product_details_repository.dart';

class GetProductDetailsUseCase implements UseCase<List<ProductDetails>, NoParams> {
  final ProductDetailsRepository repository;

  GetProductDetailsUseCase(this.repository);

  @override
  Future<List<ProductDetails>> call(NoParams params) async {
    return await repository.getProductDetails();
  }
}

class GetProductDetailsByIdUseCase implements UseCase<ProductDetails?, String> {
  final ProductDetailsRepository repository;

  GetProductDetailsByIdUseCase(this.repository);

  @override
  Future<ProductDetails?> call(String id) async {
    return await repository.getProductDetailsById(id);
  }
}

class SaveProductDetailsUseCase implements UseCase<void, ProductDetails> {
  final ProductDetailsRepository repository;

  SaveProductDetailsUseCase(this.repository);

  @override
  Future<void> call(ProductDetails productDetails) async {
    return await repository.saveProductDetails(productDetails);
  }
}

class DeleteProductDetailsUseCase implements UseCase<void, String> {
  final ProductDetailsRepository repository;

  DeleteProductDetailsUseCase(this.repository);

  @override
  Future<void> call(String id) async {
    return await repository.deleteProductDetails(id);
  }
}

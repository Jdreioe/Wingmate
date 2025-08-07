import 'package:flutter/material.dart';
import 'package:wingmate/infrastructure/models/category_item.dart';

class CategorySelector extends StatefulWidget {
  final VoidCallback onAddCategoryPressed;
  final List<CategoryItem> categories;
  final Function(CategoryItem) onCategorySelected;

  const CategorySelector({
    Key? key,
    required this.onAddCategoryPressed,
    required this.categories,
    required this.onCategorySelected,
  }) : super(key: key);

  @override
  State<CategorySelector> createState() => _CategorySelectorState();
}

class _CategorySelectorState extends State<CategorySelector> {
  CategoryItem? _selectedCategory;

  @override
  void initState() {
    super.initState();
    if (widget.categories.isNotEmpty) {
      _selectedCategory = widget.categories.first;
    }
  }

  @override
  void didUpdateWidget(covariant CategorySelector oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.categories.isNotEmpty && !_categoryExistsInList(_selectedCategory)) {
      _selectedCategory = widget.categories.first;
    }
  }

  bool _categoryExistsInList(CategoryItem? category) {
    return category != null && widget.categories.any((element) => (element as CategoryItem).id == category.id);
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: DropdownButton<CategoryItem>(
            isExpanded: true,
            value: _selectedCategory,
            hint: const Text('Select Category'),
            items: widget.categories.map<DropdownMenuItem<CategoryItem>>((CategoryItem category) {
              return DropdownMenuItem<CategoryItem>(
                value: category,
                child: Text('Category: ${category.name}'),
              );
            }).toList(),
            onChanged: (CategoryItem? newValue) {
              if (newValue != null) {
                setState(() {
                  _selectedCategory = newValue;
                });
                widget.onCategorySelected(newValue);
              }
            },
          ),
        ),
        IconButton(
          icon: const Icon(Icons.add),
          onPressed: widget.onAddCategoryPressed,
        ),
      ],
    );
  }
}

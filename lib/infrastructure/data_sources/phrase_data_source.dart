import 'package:wingmate/domain/entities/phrase.dart';
import 'package:wingmate/infrastructure/data/phrase_item_dao.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

abstract class PhraseDataSource {
  Future<List<Phrase>> getPhrases();
  Future<Phrase?> getPhraseById(int id);
  Future<void> savePhrase(Phrase phrase);
  Future<void> deletePhrase(int id);
}

class PhraseLocalDataSource implements PhraseDataSource {
  final PhraseItemDao phraseItemDao;

  PhraseLocalDataSource(this.phraseItemDao);

  @override
  Future<List<Phrase>> getPhrases() async {
    final phraseItems = await phraseItemDao.getAll();
    return phraseItems.map((item) => item.toDomain()).toList();
  }

  @override
  Future<Phrase?> getPhraseById(int id) async {
    final phraseItem = await phraseItemDao.getPhraseItemById(id);
    return phraseItem?.toDomain();
  }

  @override
  Future<void> savePhrase(Phrase phrase) async {
    final phraseItem = PhraseItem.fromDomain(phrase);
    if (phrase.id == null) {
      await phraseItemDao.insert(phraseItem);
    } else {
      await phraseItemDao.updateItem(phraseItem);
    }
  }

  @override
  Future<void> deletePhrase(int id) async {
    await phraseItemDao.deleteItem(id);
  }
}

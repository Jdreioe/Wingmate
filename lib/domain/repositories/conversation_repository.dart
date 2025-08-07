import 'package:wingmate/domain/entities/said_text.dart';

abstract class ConversationRepository {
  Future<List<SaidText>> getConversations();
}
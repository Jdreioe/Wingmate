part of 'main_page_bloc.dart';

@immutable
abstract class MainPageEvent {}

class LoadMainPage extends MainPageEvent {}

class TogglePlayPause extends MainPageEvent {
  final String text;
  TogglePlayPause(this.text);
}

class AddXmlTag extends MainPageEvent {
  final String tag;
  AddXmlTag(this.tag);
}

class HandleSaveMessage extends MainPageEvent {
  final String message;
  final String category;
  final bool categoryChecked;
  HandleSaveMessage(this.message, this.category, this.categoryChecked);
}

class DeleteItem extends MainPageEvent {
  final int index;
  DeleteItem(this.index);
}

class ReorderItems extends MainPageEvent {
  final int oldIndex;
  final int newIndex;
  ReorderItems(this.oldIndex, this.newIndex);
}

class SelectFolder extends MainPageEvent {
  final int categoryId;
  final String folderName;
  SelectFolder(this.categoryId, this.folderName);
}

class SelectRootFolder extends MainPageEvent {}

class PlaySpeechItem extends MainPageEvent {
  final PhraseItem item;
  PlaySpeechItem(this.item);
}

class AddTextToInput extends MainPageEvent {
  final String text;
  AddTextToInput(this.text);
}

class SpeakFromInput extends MainPageEvent {
  final String text;
  SpeakFromInput(this.text);
}

class StopPlayback extends MainPageEvent {}

class OnReplayPressed extends MainPageEvent {}

class AddBreak extends MainPageEvent {
  final int breakTime;
  AddBreak(this.breakTime);
}

class AddPhrase extends MainPageEvent {}

class EditPhrase extends MainPageEvent {
  final PhraseItem phraseItem;
  EditPhrase(this.phraseItem);
}

class UpdatePrimaryLanguage extends MainPageEvent {
  final String language;
  UpdatePrimaryLanguage(this.language);
}

class UpdateSecondaryLanguage extends MainPageEvent {
  final String language;
  UpdateSecondaryLanguage(this.language);
}

class UpdateUiSettings extends MainPageEvent {
  final UiSettings uiSettings;
  UpdateUiSettings(this.uiSettings);
}

class AddCategory extends MainPageEvent {
  final String categoryName;
  AddCategory(this.categoryName);
}

class ToggleTextFieldExpanded extends MainPageEvent {}

class UpdateKeyboardVisibility extends MainPageEvent {
  final bool isVisible;
  UpdateKeyboardVisibility(this.isVisible);
}

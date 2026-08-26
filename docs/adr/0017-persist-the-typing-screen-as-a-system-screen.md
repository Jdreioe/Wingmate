# Persist the Typing Screen as a system Screen

Wingmate persists the Typing Screen through the ordinary Screen graph and marks it with a typed `Typing` Screen kind instead of introducing a separate layout store. The stored graph owns one template Page, its Page-element layout, and its Action-strip Buttons; runtime projection supplies live Pages from Phrase, Category, and History repositories. The Screen kind keeps this system Screen out of library, deletion, startup-selection, and vocabulary-export flows while Complete Backup still preserves its customization.

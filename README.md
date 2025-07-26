# Wingmate

Wingmate is a Flutter-based AAC (Augmentative and Alternative Communication) application.

## Project Structure

The project is organized into the following directories:

- `lib/`: Contains the main application code.
  - `app.dart`: The main application widget (`MyApp`).
  - `main.dart`: The main entry point of the application.
  - `core/`: Core services and initialization logic.
    - `app_initializer.dart`: Handles Firebase and Hive initialization.
    - `platform_info.dart`: Platform detection logic.
  - `config/`: Configuration-related classes.
    - `speech_service_config.dart`: The speech service configuration model.
    - `speech_service_config_adapter.dart`: The Hive adapter for the speech service configuration.
  - `data/`: Data access objects (DAOs), database setup, and data models.
    - `app_database.dart`: The database setup.
    - `..._dao.dart`: Data access objects for different data models.
    - `..._item.dart`: Data models.
  - `dialogs/`: Dialogs used in the application.
  - `l10n/`: Localization files.
  - `models/`: Data models.
  - `services/`: Business logic services.
  - `ui/`: UI-related files.
    - `pages/`: The pages of the application.
    - `widgets/`: Reusable widgets.
    - `helpers/`: UI helper classes.
- `android/`: Android-specific files.
- `ios/`: iOS-specific files.
- `web/`: Web-specific files.
- `linux/`: Linux-specific files.
- `windows/`: Windows-specific files.
- `macos/`: macOS-specific files.
- `test/`: Test files.

## Building the application

To build the application, run the following command:

```bash
flutter build apk --debug
```

This will create a debug build of the application in `build/app/outputs/flutter-apk/app-debug.apk`.# Wingmate
Wingmate is a Free and Open Source Software (FOSS) project aimed at providing an exceptional voice for people who cannot speak, using Azure Neural Voices.


## About the project
Wingmate is developed by Jonas, who has Cerebral Palsy (CP) and extensive experience with various speech devices. The current goal is to offer a high-quality, affordable communication solution that can be built cross platform using Flutter.

## Features:

- Select voice
- Select primary language
- Some XML tags
- Speak
- Bring your own Speech Resource or;
- The easy way - a subscription (a cup of coffee a month: Planned for early 2025 
- Save Sentences & Categories: Planned for early 2025
- Cache Sentences
- Offline Backup Voices: Planned for mid 2025
- Hand Gesture Recognition: Long-term goal
- Eye tracking support: Long-term goal

## Goals before I can launch on Play Store:
- Subscription
- Offline voices
- Testers!!!

## How to setup (the DYI version):

- Install the app
- Make a free Microsoft Azure account (portal.azure.com) 
- Create a Speech Resource (F0 = 500k free characters a month - that's enough for me)
- Add the region & key to the "Profile" dialog in the app
- Choose your voice in the Settings-page & select your primary language if it'a a multilingual voice. 

License: GPL 3.0

Credits: 

**Logo:** Anna Thaulov

**Testers:** 
Jens Juul


**Name idea:** Jeppe Forchmann's awesome documentary **_Wingman_** 

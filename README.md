# Wingmate

Wingmate is an FOSS project that lets people who cannot speak, get an actually great voice via Azure Neural Voices.

Wingman is currently developed by me, Jonas Dreiøe, who has CP and used to have a Rolltalk, then a GridPad, then a Tobii i-13 speech device. The current Tobii Windows PC cost ~50k DKK (for touch only), has a 7. gen intel processor, is so slow and has a program UI that feels like it was from Vista-era. GridPad was constantly crashing due to their program being 32-bit (in 2023) and was super slow, but felt more modern in its UX (more like Windows 7). Rolltalk stopped development in 2020, had a XP-era UI and was super buggy. So, in September of 2024, I decided to make my own AAC-program. I wanted to make it open source, so we can develop a better user-experience together!

Since it's now using Flutter, the app is able to be built cross platform.

The app is in _VERY_ early stages

## Planned features:
- Save sentences & categories - early 2025
- Cache sentences - early 2025
- Offline (backup) voices - mid 2025
- Recgonition of hand gestures (LONG TERM PLAN)
- Subscription in addition to DYI setup 

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

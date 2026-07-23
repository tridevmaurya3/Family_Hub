# Family Hub

A private, offline-first family organizer for Android, built in Java and XML.

## Version 0.3.1

- Family, Reminders, and Finance use one fixed floating add button above bottom navigation.
- Header add buttons were removed and existing add/edit dialogs are reused.
- Lists include extra bottom space so their final item remains visible above the button.
- The floating button is hidden on Home, More, and secondary screens.
- The complete Gradle wrapper is included so Android Studio can sync and run the project.
- Document Vault supports persisted PDF/image selection, search, open, and removal without deleting originals.
- Password Vault encrypts usernames, passwords, and notes with AES-GCM keys held by Android Keystore.
- The More hub includes Documents, Password Vault, Family Live, theme, backup guidance, and privacy information.
- Dashboard search and live member/document counts are connected to the Room database.

## Foundation in this version

- Original Material 3 visual system branded as **Family Hub**
- Android 12+ compatible splash screen
- Main activity with five-tab bottom navigation
- Dashboard with family status, quick actions, reminders, and finance snapshot
- Feature-first package structure ready for Room, WorkManager, and later cloud sync
- Complete offline Family Members module: add, edit, delete, search, validation, and Room persistence
- Complete offline Finance module: income and expense entries, month summary, add, edit, delete, search, validation, and Room migration
- Complete offline Reminders module: add, edit, delete, search, daily repeat, notification scheduling, and restart restoration

## Open it in Android Studio

1. Open this `FamilyHub` folder.
2. Let Android Studio download the Gradle dependencies.
3. Select an Android 8.0+ emulator or physical device and press **Run**.

The application id is `com.tridev.familyhub`. Change it before a Play Store release if required.

## Planned data boundary

Family Members, Finance, and Reminders save data in the on-device Room database. Each module is added through a migration so existing private data remains intact.

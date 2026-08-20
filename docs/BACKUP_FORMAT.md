# Wingmate offline backup format

Wingmate backups use the `.wingmate-backup` extension and are ZIP archives. Version 1 contains:

- `manifest.json`: format/version, creation time, file sizes, and SHA-256 checksums.
- `data/user-data.json`: Screens, Pages, phrases, categories, settings, voice selection, History, and the pronunciation dictionary.
- `media/`: local images, sounds, and recordings referenced by the data payload.

Absolute device paths are replaced with archive-relative media paths and remapped when restored. Speech-service credentials, Editing access verifiers, analytics identifiers, derived symbol downloads, and temporary speech caches are deliberately excluded. A restored installation asks for required credentials again. Backup archives are not encrypted and may contain private communication data, so they must be stored accordingly.

Import validates archive paths, bounds, schema compatibility, references, sizes, and checksums before replacing user data. Repository updates are rolled back to a pre-restore snapshot if applying the validated backup fails.

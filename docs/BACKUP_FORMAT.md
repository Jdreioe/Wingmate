# Wingmate offline backup format

Wingmate backups use the `.wingmate-backup` extension and are ZIP archives. Version 1 contains:

- `manifest.json`: format/version, creation time, file sizes, and SHA-256 checksums.
- `data/user-data.json`: boards, screen sets, phrases, categories, settings, voice selection, history, pronunciation dictionary, and Azure speech configuration.
- `media/`: local images, sounds, and recordings referenced by the data payload.

Absolute device paths are replaced with archive-relative media paths and remapped when restored. Azure endpoint and subscription-key configuration is included at the user's request. Editing access-code verifiers, analytics identifiers, derived symbol downloads, and temporary speech caches are deliberately excluded. Backup archives are not encrypted and must be stored accordingly.

Import validates archive paths, bounds, schema compatibility, references, sizes, and checksums before replacing user data. Repository updates are rolled back to a pre-restore snapshot if applying the validated backup fails.

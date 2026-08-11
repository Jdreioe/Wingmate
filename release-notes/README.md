# Tagged Play Store release notes

Add localized Play Store notes before creating a release tag. The directory name
must match the beta tag and each text filename must be a Google Play locale:

```text
release-notes/
└── v0.8.0/
    ├── en-US.txt
    └── da-DK.txt
```

Each file must contain between 1 and 500 characters. A `v0.8.0` tag publishes
these notes to the open-testing `beta` track. A `production-v0.8.0` tag on the
same commit promotes that beta release and prepares the same notes for production.

The former automatic deployment from the `staging` branch is replaced by this
tagged flow so every Play artifact has a stable semantic version and a
deterministic Android version code.

## Release sequence

```bash
git tag -a v0.8.0 -m "Wingmate 0.8.0 open beta"
git push origin v0.8.0

# After open beta testing succeeds, tag the exact same commit:
git tag -a production-v0.8.0 v0.8.0 -m "Promote Wingmate 0.8.0 to production"
git push origin production-v0.8.0
```

The production job uses the protected `google-play-production` GitHub
environment. Configure required reviewers for that environment in the repository
settings if production promotion should require manual approval.

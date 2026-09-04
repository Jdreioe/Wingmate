# Own the release and version contract in one place

Version sources previously conflicted across clients and release tags. Wingmate keeps one semantic version in `version.properties`, read by all clients; release tags must match it while platform build numbers remain separate. Android and iOS share one tag flow: a `vX.Y.Z` tag triggers the beta channel (iOS TestFlight, Android beta) and a `production-vX.Y.Z` tag triggers production.

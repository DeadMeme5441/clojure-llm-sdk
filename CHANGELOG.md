# Changelog

All notable user-visible changes are tracked here.

## Unreleased

### Added

- Added `:kimi-code`, an OpenAI Chat Completions compatible provider for Kimi Code at `https://api.kimi.com/coding/v1`.
- Added proper public library docs for getting started, API reference, provider configuration, and model registry behavior.
- Added GitHub CI for lint, unit tests, and jar build.
- Added `.env.example`, contribution policy, security policy, issue templates, PR template, and Dependabot configuration.

### Changed

- Split Moonshot Kimi and Kimi Code credentials: `:kimi` now reads `MOONSHOT_API_KEY`; `:kimi-code` reads `KIMI_API_KEY`.
- Added generated tool/cache ignore rules so local clj-kondo and LSP caches do not appear as candidate repo files.

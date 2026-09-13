# Security Policy

## Supported Versions

Security fixes target `main` and the most recent version documented in the README. Older release lines may require upgrading before a fix is applied.

## Reporting a Vulnerability

Do not open a public issue for a suspected vulnerability.

Use GitHub private vulnerability reporting for this repository when available. If that is not available, contact the maintainer through the GitHub repository owner profile and include:

- A short description of the issue and affected provider or modality.
- Reproduction steps that do not expose real API keys.
- The expected impact.
- Any known mitigation.

Please do not include provider API keys, OAuth tokens, service-account JSON, `.codex/auth.json`, captured authorization headers, or full raw responses that may contain secrets.

## Secret Handling

The SDK reads credentials from environment variables or caller-provided profile configuration. It does not intentionally log authorization headers or token values. Issues, PRs, test fixtures, and docs must use redacted or synthetic credentials only.

ChatGPT OAuth (`:codex-backend`) can read managed Codex CLI `auth.json` credentials
or accept a caller-managed bearer and account id. Managed refresh preserves
unrelated file fields and replaces rotated credentials atomically with owner-only
permissions. Refresh failures do not log token responses. Authentication helpers
return credential values to callers; do not log those maps.

Use one managed credential file per runner. SDK calls in one process coordinate
refreshes, but this does not serialize independent Codex CLI or other application
processes. For shared or keyring-backed authentication, supply caller-managed
tokens and let that credential owner handle rotation. Never commit `auth.json`,
its backups, OAuth refresh responses, or live HTTP authorization headers.

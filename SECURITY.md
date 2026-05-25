# Security Policy

## Supported Versions

Until the first stable release, security fixes target `main` and the latest published Git SHA. After versioned releases begin, this file will list supported release lines explicitly.

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

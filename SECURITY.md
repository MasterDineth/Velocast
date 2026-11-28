# Security Policy

## Pre-Release Notice

VeloCast is currently **pre-release** software. Session authentication (`SEC-01`) and `AES-256-GCM` transport encryption + `AndroidKeyStore` secret protection (`SEC-02`) are implemented and documented in [docs/security.md](docs/security.md); remaining launcher and release-hardening items are tracked in [docs/findings.md](docs/findings.md) and [docs/steps.md](docs/steps.md).

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, report vulnerabilities privately via GitHub's **Security Advisories** ("Report a vulnerability" on the repository's **Security** tab) or by contacting the repository maintainer directly.

Please include:
- A description of the vulnerability and its potential impact.
- Steps to reproduce or a minimal proof of concept.
- Affected module(s) and commit/branch if known.

We will acknowledge receipt and coordinate a fix before public disclosure.

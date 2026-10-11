# Security policy

This repository contains the source of an Android client for third-party AI APIs.
API keys must be supplied by each user at runtime; do not commit keys, signing
material, conversation exports, database files, screenshots with credentials,
or private attachments.

## Reporting a vulnerability

Please do **not** open public issues or pull requests that disclose a usable
secret, private user data, or unpatched exploit details.

If GitHub **Private vulnerability reporting** is enabled, use the repository's
**Security → Report a vulnerability** flow. Otherwise, contact the maintainer
privately through an already established channel before sharing sensitive
details. Do not paste secrets into a public comment or issue to request contact.

## Before publishing changes

- Check both the changed files and Git history for credentials.
- Do not add real credentials to automated tests, logs, build configurations,
  assets, or documentation; use inert examples.
- Treat .gitignore as an accident-prevention measure, not a secret scanner.
- GitHub Actions signing credentials should remain in repository secrets and
  must never be printed or uploaded as build artifacts.
- If a secret is exposed, revoke/rotate it first; deleting a current file is
  not enough when the value remains in Git history or Actions logs.

Please avoid attaching unredacted application logs or model conversations
to public bug reports.

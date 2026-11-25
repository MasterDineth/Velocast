# Contributing to VeloCast

Thank you for contributing to VeloCast! Please read [AGENTS.md](AGENTS.md) and [docs/README.md](docs/README.md) before making changes.

## Branch Naming

Use one branch per implementation step, named:

```text
step/Sxx-name
```

For example: `step/S00-hygiene`, `step/S01-restructure`.

Keep commits small, focused, and reviewable with clear messages.

## Build, Test, and Format Commands

Run the Gradle wrapper from the repository root (JDK 17):

- **Build debug APKs:**
  ```bash
  ./gradlew assembleDebug
  ```
- **Run Android Lint:**
  ```bash
  ./gradlew lint
  ```
- **Run full verification (lint, format, static analysis, unit tests — once configured):**
  ```bash
  ./gradlew check
  ```
- **Run fast protocol unit tests (once `:protocol` is extracted):**
  ```bash
  ./gradlew :protocol:test
  ```

## Pull Requests

- Target `main` from your `step/Sxx-name` branch.
- Complete the checklist in [.github/pull_request_template.md](.github/pull_request_template.md).
- Never disable a test or lint rule to make a build pass.
- Do not log secrets, PINs, or full screen content.
- List any checks that require a physical device separately and do not claim device tests you did not run.

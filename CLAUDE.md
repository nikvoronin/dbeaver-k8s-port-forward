# Project Instructions

This repository contains the `dbeaver-k8s-port-forward` project.

## Primary task

The detailed implementation specification is in:

`TASK.md`

Always read `TASK.md` completely before making architectural or implementation decisions.

## Working rules

- Work only on this repository and explicitly created reference/check-out directories.
- Do not overwrite unrelated user changes.
- Inspect files before modifying them.
- Do not invent DBeaver APIs.
- Verify DBeaver APIs against the current DBeaver Community source.
- Do not copy proprietary DBeaver PRO code.
- Keep the implementation minimal and maintainable.
- Run tests and builds yourself instead of only describing commands.
- Fix compilation and test failures before declaring completion.
- Never claim a test or integration check passed unless it was actually executed successfully.

## Reference source

If DBeaver Community source is required for research, clone it under:

`.reference\dbeaver`

Do not copy the DBeaver repository into the production plugin source tree.

The `.reference` directory is intentionally git-ignored.

## Windows environment

This project is being developed on Windows.

Prefer native Windows-compatible commands and paths.

Use PowerShell when a shell is needed.

Do not assume Bash, WSL, GNU coreutils, or Unix-only filesystem semantics are available.

Any fake kubectl process used by tests should be portable to Windows where practical.

## Git

Use git to inspect changes frequently.

Do not commit unless explicitly asked.

Never discard existing user changes with destructive git commands.

## Temporary files

Put temporary/research files under `.tmp\` or `.reference\` where practical.

Remove unnecessary temporary files before finishing.

## Java and build environment

This is a Windows development environment.

JDK 25 is installed and should be treated as the baseline unless research
into the current DBeaver source proves that another version is required.

A globally installed Maven executable must not be assumed.

If Maven/Tycho is selected for the plugin build, prefer adding and using
Maven Wrapper so the repository can be built on Windows with:

    .\mvnw.cmd clean verify

Do not require the developer to manually install Maven unless Maven Wrapper
is technically unsuitable.

Use Windows-native tooling and PowerShell-compatible commands.
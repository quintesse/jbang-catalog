# Project guide

## Purpose and structure

This is Quintesse's catalog of standalone Java scripts run with JBang, licensed
under Apache-2.0. It has no shared Maven or Gradle build. Work on individual
scripts and their catalog entries rather than introducing an application layout.

- `jbang-catalog.json`: published aliases and the linked experimental catalog.
  Most aliases point to local Java files; `refactor` points to a Maven artifact.
- Root Java files: basic utilities (`cat`, `echo`, `hello`, `jget`), system and
  network tools (`jvmci`, `system_env`, `system_properties`, `mdns`,
  `simple_httpd`), JSON/REST tools (`ijq`, `rest`), and developer tools
  (`mvn2jbang`, `redir`, `runall`). Alias names can differ from filenames.
- `experimental/`: a separate catalog plus Java feature demos, terminal UIs,
  HTTP servers/proxies, service integrations, and native C interop examples.
  Some files are deliberately version-specific or require preview/incubator APIs.
  Not every experimental file is registered as an alias.
- `tests/ijqTest.java` and `tests/ijqTest.json`: JUnit 5 tests and fixture for `ijq`.
- `experimental/runall_tests/`: sample programs for exercising parallel execution.
- `experimental/quickserv/`: shell scripts and web assets for server experiments.
- `.github/renovate.json`: tracks Maven coordinates in Java `//DEPS` directives
  and catalog JSON using custom regex managers.

## Editing conventions

- Preserve each script's existing style, launcher header, and JBang directives.
  Dependencies belong in `//DEPS`; Java requirements belong in `//JAVA` and
  compiler/runtime options in their corresponding directives.
- Inspect the target script's Java requirements before running or modifying it.
  There is no single Java version that covers all experiments.
- Add or update the appropriate catalog entry when changing a published alias.
  Keep descriptions accurate; they feed the generated README.
- Keep changes focused. Avoid unrelated formatting or dependency upgrades.
- Consider Windows and Unix behavior for file paths, shell commands, process
  execution, and terminal handling. This project is also used from PowerShell.
- Do not commit generated classes, native libraries, IDE files, caches, or
  credentials. Consult `.gitignore`; `experimental/telegram-token.sh` is ignored.

## Running and checking changes

Run commands from the repository root with JBang available on PATH:

```text
jbang hello.java
jbang tests/ijqTest.java
git diff --check
```

The test script launches JUnit itself and declares its dependencies and fixture.
Use it for changes to `ijq`. For other scripts, select a small, relevant smoke
check after reading their behavior and arguments. Some tools modify files,
contact external services, or start long-running servers; choose inputs carefully.
There is no general script test workflow in the repository. The build-only
`.github/workflows/check_scripts.yml` checks affected JBang scripts on PRs,
compares failures against the target commit, and inventories baseline metadata
on `main`. It does not run script main methods. See `.github/scripts/README.md`
for selection rules, reports, and local commands. The CI helper has focused
tests: `python .github/scripts/test_check_jbang.py`.

For catalog-only changes, validate JSON and check that local script references
exist. In PowerShell, JSON parsing can be checked with:

```powershell
Get-Content jbang-catalog.json -Raw | ConvertFrom-Json | Out-Null
Get-Content experimental/jbang-catalog.json -Raw | ConvertFrom-Json | Out-Null
```

Report the checks actually performed and any limitations. First runs can require
network downloads of dependencies or JDKs.

## Branch and README workflow

- All commit messages and PR titles MUST use Conventional Commits:
  `type(scope): description`, with an optional scope and `!` for breaking changes.
  Use an appropriate type such as `feat`, `fix`, `docs`, `refactor`, `test`,
  `ci`, or `chore`. This applies to automated commits and PRs as well.
  Examples: `fix(ci): upload JBang build logs` and `docs: update README`.
- GitHub repository: `quintesse/jbang-catalog`.
- The default branch is `main` (renamed from `master` on 2026-10-01).
  Use `main` for repository-owned raw GitHub URLs and workflow triggers.
  Do not replace `master` in external repository URLs or Keycloak realm names.
- `.github/workflows/update_readme.yml` runs on pushes to `main` and manual
  dispatch. It generates `README.md` using:

  ```text
  jbang catalog2readme@jbangdev -l quintesse/jbang-catalog
  ```

  The workflow redirects the output to `README.md` and opens/updates a PR from
  `update-readme`; generating the file does not merge it into the default branch.
  Prefer updating catalog descriptions and using this workflow for alias docs.
- To run it manually: `gh workflow run update_readme.yml --ref main`.
  Review the resulting diff and follow the user's authorization for merging.
- At the branch migration, push and manual runs succeeded and README PR #30
  was merged, documenting `runall`, `refactor`, and `ijq`.
- GitHub settings and authentication can change. Recheck with `gh` when needed
  instead of assuming the current session's permissions persist.

Keep this guide current when project structure or maintenance conventions change.

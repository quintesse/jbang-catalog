# JBang build checks

`check_scripts.yml` compiles scripts with `jbang build`; it does not invoke their
main methods or run the ijq tests. JBang selects Java using its defaults and each
script's directives. The workflow pins JBang in `JBANG_VERSION`; update that
value to upgrade both baseline and PR builds together.

## Pull requests

The helper finds changed files from the PR's merge base to its head, then builds
affected entry scripts in GitHub's proposed merge checkout. Discovery uses
launcher headers, main methods, catalog targets, and standalone JBang directives.
Generated/supporting sources are followed through their owning scripts.

Selection follows local `//SOURCES`, `//FILES`, and script `//DEPS` declarations
transitively in both the target and proposed merge checkouts. It retains wildcard
patterns and resource directories, including inputs added or deleted by the PR.
Resolved source/resource metadata supplements those declarations when an
exact-target-commit, matching-JBang-version manifest is available. Missing,
expired, or incompatible metadata falls back to declaration discovery. Dynamic
or unsupported local path declarations broaden selection to all entry scripts
and appear in the report.

Changes to the helper or workflow also build all entries, comparing failures
against the target. Documentation-only changes select no scripts. Catalog JSON
and local script references are checked on every run; new or changed local
catalog targets are selected. External aliases/artifacts are not fetched for
catalog validation. Compilation checks local scripts, not catalog-specific
launcher arguments or runtime behavior.

A failed build is repeated against the target commit, using the old path for a
renamed script:

| Proposed merge | Target | Check result |
| --- | --- | --- |
| Pass | Not built | Pass |
| Fail | Pass | Regression: fail |
| Fail | No entry script | New script failure: fail |
| Fail | Fail | Existing failure: report, without failing the gate |
| Timeout | Any | Incomplete: fail |

Failures on both sides are inconclusive: a PR may introduce additional problems
in an already broken experiment. Both logs are retained for review. Each JBang
command has a five-minute timeout, and the job has a 90-minute overall limit.

## Baseline metadata

Pushes to `main` and manual runs inventory all entry scripts. Existing build
failures are recorded without failing the inventory; catalog errors still fail.
Only successful builds are followed by `jbang info tools`, since information from
an unbuilt script may be incomplete. Metadata warnings or invalid JSON mark the
entry incomplete and retain the declaration fallback.

The workflow saves metadata in an Actions cache keyed by target commit, OS,
manifest format, and JBang version. No approximate cache keys are restored.
Only non-PR runs on `main` publish that cache. All runs upload a summary,
manifest, and logs as `jbang-build-results`, retained for 30 days. Cache eviction
does not prevent selective checks.

Bootstrap the inventory after the workflow is merged:

```text
gh workflow run check_scripts.yml --ref main
```

Review the first report before making `JBang build regressions` a required
branch-protection check. The workflow uses Linux and read-only repository
permissions. Compilation may execute build integrations or annotation
processors, so PR builds receive no repository secrets.

## Local checks

From the repository root:

```text
python .github/scripts/test_check_jbang.py
python .github/scripts/check_jbang.py --mode baseline --output .jbang-check-results
```

The first command tests CI selection and reporting only, without invoking JBang.
The second compiles the full inventory and may download dependencies and JDKs.

To check a PR locally, create a separate checkout at the target commit, fetch the
PR head into the current repository, and check out its proposed merge. Then run:

```text
python .github/scripts/check_jbang.py --mode pr --base-root PATH_TO_TARGET_CHECKOUT --base TARGET_SHA --head PR_HEAD_SHA --output .jbang-check-results
```

An optional `--manifest PATH_TO_MANIFEST` supplements declaration discovery.
The target checkout must correspond to `TARGET_SHA`. Results and temporary CI
checkouts are ignored by Git.

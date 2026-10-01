#!/usr/bin/env python3
"""Compile affected JBang scripts; never invoke their main methods."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys


FORMAT = 1
SCRIPT_SUFFIXES = {".java", ".jsh", ".kt", ".groovy"}
DIRECTIVE = re.compile(r"^//(SOURCES|FILES|DEPS)\s+(.+)$")
ENTRY_DIRECTIVE = re.compile(r"^//(?:DEPS|JAVA|SOURCES|FILES|JAVAC_OPTIONS|COMPILE_OPTIONS|PREVIEW)\b", re.M)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args]).decode("utf-8")


def tracked(root):
    return set(filter(None, git(root, "ls-files", "-z").split("\0")))


def local_path(parent, ref):
    """Return a repository-relative declaration, or None for external refs."""
    if ":" in ref or "@" in ref:
        return None
    # Normalize separators and '..' without requiring the input to exist.
    value = os.path.normpath(str(parent / ref.replace("\\", "/"))).replace("\\", "/")
    if value.startswith("../") or value == ".." or value.startswith("/"):
        raise ValueError(f"input outside repository: {ref}")
    return value.removeprefix("./")


def catalog_entries(root, paths):
    entries, errors = {}, []
    for path in sorted(paths):
        if Path(path).name != "jbang-catalog.json":
            continue
        try:
            data = json.loads((root / path).read_text(encoding="utf-8"))
            aliases = data.get("aliases", {})
            if not isinstance(aliases, dict):
                raise ValueError("aliases must be an object")
            entries[path] = {}
            for alias, entry in aliases.items():
                ref = entry["script-ref"]
                target = local_path(Path(path).parent, ref)
                if target is not None and Path(target).suffix in SCRIPT_SUFFIXES:
                    entries[path][alias] = target
                    if target not in paths:
                        errors.append(f"{path}: alias {alias} references missing {target}")
        except (ValueError, TypeError, KeyError, AttributeError) as exc:
            errors.append(f"{path}: {exc}")
    return entries, errors


def declarations(root, path):
    """Fallback edges retain globs so newly added/deleted inputs are covered."""
    patterns, warnings = {path}, []
    text = (root / path).read_text(encoding="utf-8")
    for line in text.splitlines():
        match = DIRECTIVE.match(line)
        if not match:
            continue
        kind, value = match.groups()
        try:
            # JBang declarations use space-separated paths; support quoted paths.
            for token in shlex.split(value.replace("\\", "/"), posix=True):
                if kind == "FILES":
                    token = token.split("=", 1)[-1]
                if kind == "DEPS" and Path(token).suffix not in SCRIPT_SUFFIXES:
                    continue
                if "${" in token or "{" in token:
                    raise ValueError(f"dynamic input declaration: {token}")
                target = local_path(Path(path).parent, token)
                if target is None:
                    continue
                patterns.add(target)
                if token.endswith("/") or (root / target).is_dir():
                    patterns.add(target + "/**")
        except ValueError as exc:
            warnings.append(f"{path}: {exc}")
    return patterns, warnings


def scan(root):
    paths = tracked(root)
    catalogs, errors = catalog_entries(root, paths)
    entries = {p for aliases in catalogs.values() for p in aliases.values() if p in paths}
    graph, warnings, candidates = {}, [], set()
    # Scan supporting sources too: transitive declarations may live there.
    for path in sorted(paths):
        if Path(path).suffix not in SCRIPT_SUFFIXES:
            continue
        text = (root / path).read_text(encoding="utf-8")
        if (re.search(r"^///\s*.*\bjbang\b", text, re.M)
                or re.search(r"\bvoid\s+main\s*\(", text)):
            entries.add(path)
        elif ENTRY_DIRECTIVE.search(text):
            candidates.add(path)
        graph[path], issues = declarations(root, path)
        warnings.extend(issues)
    # A declaration-only file referenced by another source is a support file.
    for candidate in candidates:
        if not any(matches(edge, candidate) for source, edges in graph.items()
                   if source != candidate for edge in edges):
            entries.add(candidate)
    return paths, entries, graph, catalogs, errors, warnings


def matches(pattern, path):
    # Match JBang's '*' without crossing directory separators; '**' may cross.
    expression = re.escape(pattern).replace(r"\*\*", ".*").replace(r"\*", "[^/]*").replace(r"\?", "[^/]")
    return re.fullmatch(expression, path) is not None


def inputs(entry, graph, paths):
    result, visited, pending = set(), set(), [entry]
    while pending:
        node = pending.pop()
        if node in visited:
            continue
        visited.add(node)
        for pattern in graph.get(node, {node}):
            result.add(pattern)
            pending.extend(p for p in paths if p in graph and matches(pattern, p) and p not in visited)
    return result


def changed_files(root, base, head):
    """Both old and new names are relevant, including deletions."""
    fields = git(root, "diff", "--name-status", "-z", "--find-renames", base, head).split("\0")
    changed, renames = set(), {}
    index = 0
    while index < len(fields) and fields[index]:
        status, old = fields[index:index + 2]
        index += 2
        changed.add(old)
        if status.startswith(("R", "C")):
            new = fields[index]
            index += 1
            changed.add(new)
            if status.startswith("R"):
                renames[new] = old
    return changed, renames


def metadata_inputs(root, info):
    result = set()
    for key in ("sources", "files"):
        for resource in info.get(key, []):
            backing = resource.get("backingResource")
            if backing:
                try:
                    result.add(Path(backing).resolve().relative_to(root.resolve()).as_posix())
                except ValueError:
                    pass  # JBang cache files and remote resources aren't repository inputs.
    return result


def load_manifest(path, commit, version):
    if not path or not path.exists():
        return None
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        if (manifest.get("format"), manifest.get("commit"), manifest.get("jbang")) == (FORMAT, commit, version):
            return manifest
    except (ValueError, OSError):
        pass
    return None


def select_scripts(current, baseline, changed, manifest=None):
    paths, entries, graph, catalogs, _, warnings = current
    old_paths, _, old_graph, old_catalogs, _, old_warnings = baseline
    reasons = {}
    broad = any(p.startswith(".github/scripts/") or p == ".github/workflows/check_scripts.yml" for p in changed)
    if warnings or old_warnings:
        broad = True  # Unsupported declarations must never silently omit a script.
    for entry in sorted(entries):
        edges = inputs(entry, graph, paths)
        edges |= inputs(entry, old_graph, old_paths)
        if manifest:
            edges.update(manifest.get("scripts", {}).get(entry, {}).get("inputs", []))
        affected = sorted(p for p in changed if any(matches(edge, p) for edge in edges))
        if broad:
            reasons[entry] = "CI/discovery configuration changed or declaration coverage incomplete"
        elif affected:
            reasons[entry] = ", ".join(affected)
    for catalog in changed & (catalogs.keys() | old_catalogs.keys()):
        before, after = old_catalogs.get(catalog, {}), catalogs.get(catalog, {})
        for alias, target in after.items():
            if before.get(alias) != target and target in entries:
                reasons[target] = f"catalog target changed: {catalog} ({alias})"
    return reasons


def command(root, args, log, timeout):
    """Logs are data; don't forward compiler output as Actions commands."""
    executable = shutil.which("jbang")
    if not executable:
        raise RuntimeError("JBang is not on PATH")
    try:
        completed = subprocess.run([executable, *args], cwd=root, capture_output=True,
                                   text=True, encoding="utf-8", errors="replace", timeout=timeout)
        log.write_text(completed.stdout + completed.stderr, encoding="utf-8")
        return completed.returncode, completed.stdout, completed.stderr
    except subprocess.TimeoutExpired as exc:
        def decode(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
        log.write_text(decode(exc.stdout) + decode(exc.stderr) + "\nBuild timed out\n", encoding="utf-8")
        return 124, "", "Build timed out"


def build(root, entry, output, label, timeout):
    stem = hashlib.sha256(entry.encode()).hexdigest()[:12]
    prefix = output / f"{stem}-{label}"
    code, _, _ = command(root, ["build", entry], prefix.with_suffix(".log"), timeout)
    result = {"build": "pass" if code == 0 else "fail", "exit_code": code,
              "log": prefix.with_suffix(".log").name, "inputs": [], "metadata_complete": False}
    if code == 0:
        code, stdout, stderr = command(root, ["info", "tools", entry], prefix.with_suffix(".info.log"), timeout)
        try:
            info = json.loads(stdout)
            result["inputs"] = sorted(metadata_inputs(root, info))
            result["metadata_complete"] = code == 0 and entry in result["inputs"] and "[WARN]" not in stderr
            result["requested_java"] = info.get("requestedJavaVersion")
            result["java"] = info.get("javaVersion")
        except ValueError:
            pass
    return result


def classify(current, baseline):
    if current.get("exit_code") == 124 or (baseline and baseline.get("exit_code") == 124):
        return "incomplete (build timeout)"
    if current["build"] == "pass":
        return "pass"
    if baseline is None:
        return "new script failure"
    return "regression" if baseline["build"] == "pass" else "existing failure (inconclusive)"


def markdown(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("`", "'")


def report(output, rows, notes, errors):
    lines = ["# JBang build checks", "", "Scripts are compiled only; their main methods are not run.", ""]
    if rows:
        lines += ["| Script | Selected because | Result | Log |", "| --- | --- | --- | --- |"]
        for entry, reason, result, log in rows:
            lines.append(f"| `{markdown(entry)}` | {markdown(reason)} | {markdown(result)} | `{markdown(log)}` |")
    else:
        lines.append("No affected scripts.")
    if notes:
        lines += ["", "## Discovery", "", *[f"- {markdown(note)}" for note in notes]]
    if errors:
        lines += ["", "## Catalog errors", "", *[f"- {markdown(error)}" for error in errors]]
    text = "\n".join(lines) + "\n"
    (output / "summary.md").write_text(text, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("baseline", "pr"), required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--base-root", type=Path)
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    root, output = args.root.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    version = subprocess.check_output([shutil.which("jbang") or "jbang", "--version"], text=True).strip()
    commit = git(root, "rev-parse", "HEAD").strip()
    current = scan(root)
    rows, results, notes = [], {}, [f"JBang {version}; checkout {commit}"]
    errors = current[4]
    notes.extend(current[5])
    failed = False
    if args.mode == "baseline":
        reasons = {entry: "baseline inventory" for entry in sorted(current[1])}
    else:
        if not all((args.base_root, args.base, args.head)):
            parser.error("pr mode requires --base-root, --base and --head")
        if git(args.base_root.resolve(), "rev-parse", "HEAD").strip() != args.base:
            parser.error("--base-root must be checked out at --base")
        baseline = scan(args.base_root.resolve())
        notes.extend(baseline[5])
        manifest = load_manifest(args.manifest, args.base, version)
        notes.append("Using exact-commit baseline metadata." if manifest else
                     "No matching baseline metadata; using local declarations from both checkouts.")
        merge_base = git(root, "merge-base", args.base, args.head).strip()
        changed, renames = changed_files(root, merge_base, args.head)
        reasons = select_scripts(current, baseline, changed, manifest)
    for entry, reason in reasons.items():
        print(f"Building {entry}", flush=True)
        result = build(root, entry, output, "current", args.timeout)
        results[entry] = result
        if args.mode == "baseline":
            status = result["build"]
        else:
            old = None
            if result["build"] != "pass":
                old_entry = renames.get(entry, entry)
                if old_entry in baseline[1]:
                    old = build(args.base_root.resolve(), old_entry, output, "base", args.timeout)
                    result["baseline"] = old
            status = classify(result, old)
            failed |= status in {"regression", "new script failure", "incomplete (build timeout)"}
        result["classification"] = status
        if not result["metadata_complete"]:
            notes.append(f"{entry}: metadata unavailable/incomplete; declaration fallback retained.")
        logs = result["log"]
        if result.get("baseline"):
            logs += ", " + result["baseline"]["log"]
        rows.append((entry, reason, status, logs))
    manifest = {"format": FORMAT, "commit": commit, "jbang": version,
                "platform": sys.platform, "scripts": results}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    report(output, rows, notes, errors)
    # Baseline failures are an inventory, not a gate; broken catalogs still fail.
    return int(failed or bool(errors))


if __name__ == "__main__":
    sys.exit(main())

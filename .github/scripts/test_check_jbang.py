"""Tests for CI selection/reporting, not for the catalog's experiments."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("check_jbang", Path(__file__).with_name("check_jbang.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("main.java", '///usr/bin/env jbang "$0" "$@" ; exit $?\n//SOURCES lib/*.java\n//FILES data/\n')
        self.write("lib/Helper.java", "//SOURCES Nested.java\nclass Helper {}\n")
        self.write("lib/Nested.java", "class Nested {}\n")
        self.write("data/settings.json", "{}")
        self.write("test.java", "//DEPS main.java\nclass Test {}\n")
        self.write("unrelated.java", "class Unrelated { void main() {} }\n")
        self.write("jbang-catalog.json", json.dumps({"aliases": {"main": {"script-ref": "main.java"}}}))

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def scan(self):
        paths = {p.relative_to(self.root).as_posix() for p in self.root.rglob("*") if p.is_file()}
        with patch.object(checks, "tracked", return_value=paths):
            return checks.scan(self.root)

    def selected(self, changed):
        state = self.scan()
        return set(checks.select_scripts(state, state, changed))

    def test_transitive_local_script_dependency(self):
        self.assertEqual(self.selected({"lib/Nested.java"}), {"main.java", "test.java"})

    def test_new_and_deleted_wildcard_sources(self):
        self.assertEqual(self.selected({"lib/New.java"}), {"main.java", "test.java"})
        before = self.scan()
        (self.root / "lib/Helper.java").unlink()
        after = self.scan()
        self.assertIn("main.java", checks.select_scripts(after, before, {"lib/Helper.java"}))

    def test_resource_directory(self):
        self.assertEqual(self.selected({"data/new.json"}), {"main.java", "test.java"})

    def test_docs_only(self):
        self.assertEqual(self.selected({"README.md"}), set())

    def test_ordinary_java_main_is_discovered(self):
        self.assertEqual(self.selected({"unrelated.java"}), {"unrelated.java"})

    def test_generated_sources_are_not_entry_scripts(self):
        self.assertNotIn("lib/Nested.java", self.scan()[1])

    def test_resource_mapping_and_windows_paths(self):
        self.write("mapped.java", '//FILES META-INF/service="data/settings.json"\n//SOURCES lib\\Nested.java\n')
        graph = self.scan()[2]
        self.assertIn("data/settings.json", graph["mapped.java"])
        self.assertIn("lib/Nested.java", graph["mapped.java"])

    def test_removed_reference_uses_baseline_edges(self):
        before = self.scan()
        self.write("main.java", "//DEPS example:thing:1\n")
        self.assertIn("main.java", checks.select_scripts(self.scan(), before, {"data/settings.json"}))

    def test_manifest_adds_metadata_edges(self):
        state = self.scan()
        manifest = {"scripts": {"main.java": {"inputs": ["other.txt"]}}}
        self.assertIn("main.java", checks.select_scripts(state, state, {"other.txt"}, manifest))

    def test_stale_or_wrong_version_manifest_is_rejected(self):
        path = self.root / "manifest.json"
        path.write_text(json.dumps({"format": checks.FORMAT, "commit": "a", "jbang": "1"}))
        self.assertIsNotNone(checks.load_manifest(path, "a", "1"))
        self.assertIsNone(checks.load_manifest(path, "b", "1"))
        self.assertIsNone(checks.load_manifest(path, "a", "2"))

    def test_catalog_new_target_and_missing_target(self):
        before = self.scan()
        self.write("jbang-catalog.json", json.dumps({"aliases": {"main": {"script-ref": "unrelated.java"}}}))
        self.assertIn("unrelated.java", checks.select_scripts(self.scan(), before, {"jbang-catalog.json"}))
        self.write("jbang-catalog.json", '{"aliases":{"missing":{"script-ref":"gone.java"}}}')
        self.assertTrue(self.scan()[4])

    def test_unknown_dynamic_declaration_broadens_selection(self):
        self.write("main.java", "//SOURCES ${src}/Helper.java\n")
        state = self.scan()
        self.assertTrue(state[5])
        self.assertEqual(set(checks.select_scripts(state, state, {"any.txt"})), state[1])


class BuildTests(unittest.TestCase):
    def test_failure_classification(self):
        good, bad = {"build": "pass"}, {"build": "fail"}
        self.assertEqual(checks.classify(good, None), "pass")
        self.assertEqual(checks.classify(bad, good), "regression")
        self.assertEqual(checks.classify(bad, bad), "existing failure (inconclusive)")
        self.assertEqual(checks.classify(bad, None), "new script failure")
        self.assertEqual(checks.classify({"build": "fail", "exit_code": 124}, bad), "incomplete (build timeout)")

    def test_build_precedes_info_and_never_runs_main(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            info = json.dumps({"sources": [{"backingResource": str(root / "main.java")}],
                               "files": [{"backingResource": str(root / "resource.txt")}],
                               "requestedJavaVersion": "17+"})
            with patch.object(checks, "command", side_effect=[(0, "", ""), (0, info, "")]) as cmd:
                result = checks.build(root, "main.java", root, "current", 5)
            self.assertEqual([call.args[1] for call in cmd.call_args_list],
                             [["build", "main.java"], ["info", "tools", "main.java"]])
            self.assertEqual(result["inputs"], ["main.java", "resource.txt"])
            self.assertTrue(result["metadata_complete"])

    def test_failed_build_does_not_query_partial_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(checks, "command", return_value=(1, "", "failure")) as cmd:
                result = checks.build(root, "main.java", root, "current", 5)
            self.assertEqual(cmd.call_count, 1)
            self.assertFalse(result["metadata_complete"])

    def test_warning_marks_info_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            info = json.dumps({"sources": [{"backingResource": str(root / "main.java")}]})
            with patch.object(checks, "command", side_effect=[(0, "", ""), (0, info, "[WARN] incomplete")]):
                result = checks.build(root, "main.java", root, "current", 5)
            self.assertFalse(result["metadata_complete"])

    def test_git_diff_keeps_deletions_and_both_rename_paths(self):
        with patch.object(checks, "git", return_value="R100\0old.java\0new.java\0D\0gone.java\0"):
            changed, renames = checks.changed_files(Path.cwd(), "base", "head")
        self.assertEqual(changed, {"old.java", "new.java", "gone.java"})
        self.assertEqual(renames, {"new.java": "old.java"})


class ReportTests(unittest.TestCase):
    def run_pr(self, current_status, baseline_status, changed=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "current"
            base_root = Path(directory) / "base"
            output = Path(directory) / "results"
            root.mkdir()
            base_root.mkdir()
            state = ({"main.java"}, {"main.java"}, {"main.java": {"main.java"}}, {}, [], [])
            empty = (set(), set(), {}, {}, [], [])
            argv = ["check_jbang.py", "--mode", "pr", "--root", str(root), "--base-root", str(base_root),
                    "--base", "base", "--head", "head", "--output", str(output)]

            def fake_git(checkout, *args):
                return "base" if checkout == base_root else "merge"

            def fake_build(checkout, entry, target, label, timeout):
                status = current_status if label == "current" else baseline_status
                return {"build": status, "exit_code": 0 if status == "pass" else 1,
                        "metadata_complete": status == "pass", "inputs": [entry], "log": label + ".log"}

            with (patch.object(sys, "argv", argv),
                  patch.object(checks, "git", side_effect=fake_git),
                  patch.object(checks, "scan", side_effect=[state, state if baseline_status else empty]),
                  patch.object(checks, "changed_files", return_value=({"main.java"} if changed else set(), {})),
                  patch.object(checks, "build", side_effect=fake_build) as build,
                  patch.object(checks.subprocess, "check_output", return_value="0.141.0"),
                  patch.dict(checks.os.environ, {"GITHUB_STEP_SUMMARY": ""})):
                code = checks.main()
            return code, (output / "summary.md").read_text(), build.call_count

    def test_regression_fails_and_records_both_logs(self):
        code, summary, calls = self.run_pr("fail", "pass")
        self.assertEqual(code, 1)
        self.assertEqual(calls, 2)
        self.assertIn("regression", summary)
        self.assertIn("current.log, base.log", summary)

    def test_existing_failure_is_reported_without_failing(self):
        code, summary, calls = self.run_pr("fail", "fail")
        self.assertEqual(code, 0)
        self.assertEqual(calls, 2)
        self.assertIn("existing failure (inconclusive)", summary)

    def test_new_failure_fails_without_baseline_build(self):
        code, summary, calls = self.run_pr("fail", None)
        self.assertEqual(code, 1)
        self.assertEqual(calls, 1)
        self.assertIn("new script failure", summary)

    def test_docs_only_check_produces_report_without_building(self):
        code, summary, calls = self.run_pr("pass", "pass", changed=False)
        self.assertEqual(code, 0)
        self.assertEqual(calls, 0)
        self.assertIn("No affected scripts", summary)


if __name__ == "__main__":
    unittest.main()

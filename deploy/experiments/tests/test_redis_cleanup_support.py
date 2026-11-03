"""Local evidence/port/config checks. No Docker resources, Redis, or business traffic."""
from pathlib import Path
import copy
import hashlib
import importlib.util
import io
import json
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[1] / "redis_cleanup_support.py"
SPEC = importlib.util.spec_from_file_location("redis_cleanup_support", SOURCE)
support = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = support
SPEC.loader.exec_module(support)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.receipt = Path(self.directory.name) / "receipt.json"

    def run_fixture(self, name, **options):
        return support.record_command("LOCAL_FIXTURE", self.receipt, fixture=name, **options)

    def failure(self, name, **options):
        with self.assertRaises(support.EvidenceFailure) as caught:
            self.run_fixture(name, **options)
        value = caught.exception
        self.assertTrue(value.publication["saved"])
        self.assertEqual(value.receipt, json.loads(self.receipt.read_text()))
        self.assertEqual(hashlib.sha256(self.receipt.read_bytes()).hexdigest(), value.publication["sha256"])
        return value

    def test_success_has_complete_hashes_and_closed_streams(self):
        output = self.run_fixture("success")
        self.assertEqual(b"fixture complete\n", output.stdout.replace(b"\r\n", b"\n"))
        self.assertEqual(0, output.receipt["exitCode"])
        for name, data in [("stdout", output.stdout), ("stderr", output.stderr)]:
            receipt = output.receipt[name]
            self.assertTrue(receipt["complete"] and receipt["pipeClosed"])
            self.assertEqual("FULL_STREAM", receipt["hashScope"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), receipt["observedSha256"])
        self.assertTrue(output.publication["closedAndReadBack"])

    def test_nonzero_receipt_precedes_exception_and_classifies_without_raw_text(self):
        failure = self.failure("nonzero")
        self.assertEqual("NONZERO_EXIT", failure.primary)
        self.assertEqual(7, failure.receipt["exitCode"])
        self.assertEqual(["NETWORK_POOL_EXHAUSTED"], failure.receipt["safeFailureClasses"])
        self.assertNotIn("all predefined", self.receipt.read_text())

    def test_both_pipes_are_drained_with_independent_hashes(self):
        output = self.run_fixture("both")
        self.assertEqual(b"A" * 4096, output.stdout)
        self.assertEqual(b"B" * 4096, output.stderr)
        self.assertNotEqual(output.receipt["stdout"]["observedSha256"], output.receipt["stderr"]["observedSha256"])

    def test_unknown_and_secret_output_never_enters_receipt(self):
        output = self.run_fixture("secret")
        self.assertIn(b"fixture-canary", output.stdout)
        text = self.receipt.read_text()
        self.assertNotIn("fixture-canary", text)
        self.assertNotIn("password=", text)
        self.assertEqual([], output.receipt["safeFailureClasses"])
        self.assertFalse(output.receipt["credentialsOrCommandTextArchived"])

    def test_output_limit_fails_without_mislabeling_retained_prefix_hash(self):
        failure = self.failure("cap", cap_bytes=64)
        self.assertEqual("OUTPUT_LIMIT", failure.primary)
        self.assertTrue(any(failure.receipt[name]["truncated"] for name in ["stdout", "stderr"]))
        for name in ["stdout", "stderr"]:
            observed = failure.receipt[name]
            self.assertLessEqual(observed["retainedInMemoryBytes"], 64)
            if observed["complete"]:
                self.assertEqual("FULL_STREAM", observed["hashScope"])
            else:
                self.assertEqual("OBSERVED_PREFIX", observed["hashScope"])

    def test_timeout_kills_only_owned_local_fixture_and_confirms_exit(self):
        started = time.monotonic()
        failure = self.failure("timeout", timeout_seconds=2)
        self.assertEqual("TIMEOUT", failure.primary)
        self.assertTrue(failure.receipt["killRequested"])
        self.assertTrue(failure.receipt["exitConfirmed"])
        self.assertEqual("CONFIRMED", failure.receipt["cleanupState"])
        self.assertEqual(0, failure.receipt["readerThreadsAlive"])
        self.assertLess(time.monotonic() - started, 2)

    def test_spawn_failure_is_archived_without_exception_message(self):
        with patch.object(support.subprocess, "Popen", side_effect=OSError("fixture-canary-private-error")):
            failure = self.failure("success")
        self.assertEqual("SPAWN_OS_ERROR", failure.primary)
        self.assertFalse(failure.receipt["spawned"])
        self.assertIsNone(failure.receipt["exitCode"])
        self.assertNotIn("fixture-canary", self.receipt.read_text())

    def test_archive_failure_preserves_original_nonzero_classification(self):
        self.receipt.write_text("existing-evidence")
        with self.assertRaises(support.EvidenceFailure) as caught:
            self.run_fixture("nonzero")
        self.assertEqual("NONZERO_EXIT", caught.exception.primary)
        self.assertFalse(caught.exception.publication["saved"])
        self.assertEqual("ARCHIVE_IO_ERROR", caught.exception.publication["failure"])
        self.assertEqual("existing-evidence", self.receipt.read_text())

    def test_archive_failure_cannot_turn_success_into_pass(self):
        self.receipt.write_text("existing-evidence")
        with self.assertRaises(support.EvidenceFailure) as caught:
            self.run_fixture("success")
        self.assertEqual("ARCHIVE_FAILED", caught.exception.primary)
        self.assertFalse(caught.exception.publication["saved"])

    def test_late_spawn_reports_unknown_without_extending_deadline(self):
        class LateProcess:
            pid = 123456789
            stdout = io.BytesIO()
            stderr = io.BytesIO()
            def poll(self): return None
            def wait(self, timeout): raise AssertionError("late child cannot get a new wait window")
            def kill(self): raise AssertionError("late child cannot get an unbounded cleanup window")
        ticks = iter([0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2])
        with patch.object(support.subprocess, "Popen", return_value=LateProcess()), patch.object(support.time, "monotonic", side_effect=lambda: next(ticks, 2)):
            failure = self.failure("success", timeout_seconds=1)
        self.assertEqual("LATE_SPAWN", failure.primary)
        self.assertEqual("UNKNOWN", failure.receipt["cleanupState"])
        self.assertFalse(failure.receipt["exitConfirmed"])
        self.assertEqual("OBSERVED_PREFIX", failure.receipt["stdout"]["hashScope"])

    def test_resource_commands_and_arbitrary_fixtures_are_rejected(self):
        for kind, fixture in [("COMPOSE_UP", None), ("LOCAL_FIXTURE", "arbitrary-code")]:
            with self.assertRaisesRegex(ValueError, "COMMAND_NOT_ALLOWED"):
                support.record_command(kind, self.receipt, fixture=fixture)
        self.assertFalse(self.receipt.exists())

    def test_publication_cannot_extend_the_callers_deadline(self):
        real_clock = time.monotonic
        publish = support._publish
        extra = [0]
        def late_publication(path, receipt):
            published = publish(path, receipt)
            extra[0] = 11
            return published
        with patch.object(support.time, "monotonic", side_effect=lambda: real_clock() + extra[0]), patch.object(support, "_publish", side_effect=late_publication):
            with self.assertRaises(support.EvidenceFailure) as caught:
                self.run_fixture("success", timeout_seconds=10)
        self.assertEqual("DEADLINE_EXCEEDED", caught.exception.primary)
        self.assertTrue(caught.exception.publication["saved"])
        self.assertEqual("NONE", caught.exception.receipt["primaryFailure"])

    def test_exit_zero_with_incomplete_streams_remains_unknown(self):
        class MissingStreams:
            pid = 123456789
            stdout = io.BytesIO()
            stderr = io.BytesIO()
            def poll(self): return 0
            def wait(self, timeout): return 0
        with patch.object(support.subprocess, "Popen", return_value=MissingStreams()):
            failure = self.failure("success")
        self.assertEqual("CLEANUP_UNKNOWN", failure.primary)
        self.assertEqual(0, failure.receipt["exitCode"])
        self.assertEqual("UNKNOWN", failure.receipt["cleanupState"])


class PortAndConfigTest(unittest.TestCase):
    def test_low_port_bool_and_noninteger_rejected_without_bind(self):
        with patch.object(support.socket, "socket") as create:
            for value in [1755, 0, True, 19999, 60001, "30000", 30000.0]:
                with self.assertRaises(ValueError): support.check_high_port(value)
            create.assert_not_called()

    def test_exact_candidate_is_probed_once_and_never_replaced(self):
        with patch.object(support.socket, "socket") as create:
            self.assertEqual(53791, support.check_high_port(53791))
            create.return_value.__enter__.return_value.bind.assert_called_once_with(("127.0.0.1", 53791))
            create.return_value.__exit__.assert_called_once()

    def test_busy_candidate_fails_without_fallback(self):
        with patch.object(support.socket, "socket") as create:
            connection = create.return_value.__enter__.return_value
            connection.bind.side_effect = OSError("busy")
            with self.assertRaises(OSError): support.check_high_port(53791)
            connection.bind.assert_called_once_with(("127.0.0.1", 53791))
            create.assert_called_once()

    def test_derived_config_changes_only_network_and_excludes_other_services(self):
        base = {"name": "unique-preparation", "services": {"redis": {
            "image": "redis:7.0", "pull_policy": "never", "ports": [{"host_ip": "127.0.0.1", "published": "53791", "target": 6379}],
            "networks": {"lab": None}, "command": ["redis-server", "--requirepass", "root", "--appendonly", "yes"],
            "labels": {"owner": "unique"}, "extraFutureSetting": "retained"}, "mysql": {}},
            "volumes": {"redis_data": {"name": "unique-redis", "labels": {"owner": "unique"}}, "mysql_data": {}}, "networks": {"lab": {}}}
        original = copy.deepcopy(base)
        derived = support.derive_bridge_config(base, 53791)
        self.assertEqual(original, base)
        self.assertEqual({"redis"}, set(derived["services"]))
        self.assertNotIn("networks", derived)
        expected = copy.deepcopy(base["services"]["redis"])
        expected.pop("networks")
        expected["network_mode"] = "bridge"
        self.assertEqual(expected, derived["services"]["redis"])
        self.assertEqual({"redis_data": base["volumes"]["redis_data"]}, derived["volumes"])
        with self.assertRaises(ValueError): support.derive_bridge_config(base, 53792)


class ExecutablePinTest(unittest.TestCase):
    def test_missing_relative_or_wrong_executable_is_rejected_without_launch(self):
        with patch.object(support.subprocess, "Popen") as launch:
            for path, digest in [(None, None), (Path("docker.exe"), "a" * 64), (Path(tempfile.gettempdir()) / "arbitrary.exe", "a" * 64)]:
                with self.assertRaises(ValueError):
                    support.record_command("COMPOSE_CONFIG", Path("unused-receipt.json"),
                        config_path=Path("unused-compose.yml"), project="reviewed-config",
                        executable_path=path, executable_sha256=digest)
            launch.assert_not_called()

    def test_changed_executable_fails_hash_gate_without_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / "docker.exe"
            candidate.write_bytes(b"fixture-file-never-executed")
            with patch.object(support.subprocess, "Popen") as launch:
                with self.assertRaisesRegex(ValueError, "EXECUTABLE_HASH_MISMATCH"):
                    support.record_command("COMPOSE_CONFIG", Path(directory) / "receipt.json",
                        config_path=Path("unused-compose.yml"), project="reviewed-config",
                        executable_path=candidate, executable_sha256="0" * 64)
                launch.assert_not_called()

    def test_exact_identity_is_accepted_without_executing_fixture_file(self):
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / "docker.exe"
            candidate.write_bytes(b"fixture-file-never-executed")
            expected = hashlib.sha256(candidate.read_bytes()).hexdigest()
            self.assertEqual(candidate, support.check_docker_pin(candidate, expected))


if __name__ == "__main__":
    unittest.main()

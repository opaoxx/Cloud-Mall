"""Preparation-only helpers: bounded evidence, one explicit port, and bridge configuration.

This module has no Docker resource creation, Redis, or business request entry point.
"""
from __future__ import annotations

import copy
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time


FIXTURES = {
    "success": "print('fixture complete')",
    "nonzero": "import sys;sys.stderr.write('all predefined address pools have been fully subnetted\\n');sys.exit(7)",
    "both": "import os;os.write(1,b'A'*4096);os.write(2,b'B'*4096)",
    "timeout": "import time;time.sleep(30)",
    "cap": "import os;os.write(1,b'A'*131072);os.write(2,b'B'*131072)",
    "secret": "import os;os.write(1,b'Bearer fixture-canary-DO-NOT-PERSIST');os.write(2,b'password=fixture-canary-DO-NOT-PERSIST')",
}
SAFE_FAILURE_MARKERS = {
    b"all predefined address pools have been fully subnetted": "NETWORK_POOL_EXHAUSTED",
    b"ports are not available": "PORT_PUBLISH_REJECTED",
    b"address already in use": "ADDRESS_IN_USE",
}


class EvidenceFailure(RuntimeError):
    """A fixed classification with safe evidence; no command/exception text escapes."""

    def __init__(self, primary: str, receipt: dict, publication: dict):
        super().__init__(primary)
        self.primary = primary
        self.receipt = receipt
        self.publication = publication


@dataclass(frozen=True)
class RecordedOutput:
    """Complete bounded output stays in memory; the receipt stores counts and hashes."""

    stdout: bytes
    stderr: bytes
    receipt: dict
    publication: dict
    elapsed_seconds: float


class _ObservedStream:
    def __init__(self, pipe, cap: int, overflow: threading.Event):
        self.pipe = pipe
        self.cap = cap
        self.overflow = overflow
        self.count = 0
        self.digest = hashlib.sha256()
        self.prefix = bytearray()
        self.tail = b""
        self.markers = set()
        self.eof = False
        self.read_failed = False
        self.closed = False
        self.lock = threading.Lock()

    def read(self):
        try:
            while True:
                block = os.read(self.pipe.fileno(), 4096)
                if not block:
                    with self.lock:
                        self.eof = True
                    break
                with self.lock:
                    self.count += len(block)
                    self.digest.update(block)
                    self.prefix.extend(block[:max(0, self.cap - len(self.prefix))])
                    combined = self.tail + block.lower()
                    for marker, classification in SAFE_FAILURE_MARKERS.items():
                        if marker in combined:
                            self.markers.add(classification)
                    self.tail = combined[-128:]
                    if self.count > self.cap:
                        self.overflow.set()
        except (OSError, ValueError):
            with self.lock:
                self.read_failed = True

    def receipt(self):
        with self.lock:
            return {
                "observedBytes": self.count,
                "observedSha256": self.digest.hexdigest(),
                "hashScope": "FULL_STREAM" if self.eof and not self.read_failed else "OBSERVED_PREFIX",
                "complete": self.eof and not self.read_failed,
                "truncated": self.count > self.cap,
                "retainedInMemoryBytes": len(self.prefix),
                "rawTextArchived": False,
                "pipeClosed": self.closed,
                "safeFailureClasses": sorted(self.markers),
            }


def _publish(path: Path, receipt: dict):
    payload = (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode()
    try:
        with path.open("xb") as stream:
            stream.write(payload)
        readback = path.read_bytes()
        if readback != payload:
            return {"saved": False, "failure": "READBACK_MISMATCH"}
        return {"saved": True, "sha256": hashlib.sha256(readback).hexdigest(), "closedAndReadBack": True}
    except OSError:
        return {"saved": False, "failure": "ARCHIVE_IO_ERROR"}


def record_command(kind: str, receipt_path: Path, *, timeout_seconds: float = 10,
                   cap_bytes: int = 65536, fixture: str | None = None,
                   config_path: Path | None = None, project: str | None = None,
                   environment: dict | None = None, executable_path: Path | None = None,
                   executable_sha256: str | None = None) -> RecordedOutput:
    """Run only fixed local fixtures or Compose config, then publish before raising.

    Unknown output and exception messages are never persisted. A complete observed
    stream may exceed the cap: its hash covers all bytes, but it still fails the
    output limit. Incomplete stream hashes are explicitly prefix observations.
    Synchronous spawn/filesystem calls cannot be forcibly interrupted here;
    lateness is rejected after they return. This is not a resource supervisor.
    """
    if isinstance(cap_bytes, bool) or not isinstance(cap_bytes, int) or cap_bytes < 1:
        raise ValueError("INVALID_CAP")
    if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)) or not 0.1 <= timeout_seconds <= 30:
        raise ValueError("INVALID_DEADLINE")
    started = time.monotonic()
    deadline = started + timeout_seconds
    work_deadline = deadline - min(2, timeout_seconds * 0.4)
    if kind == "LOCAL_FIXTURE" and fixture in FIXTURES:
        arguments = [sys.executable, "-I", "-c", FIXTURES[fixture]]
    elif kind == "COMPOSE_CONFIG" and config_path is not None and project and all(
            character in "abcdefghijklmnopqrstuvwxyz0123456789-" for character in project):
        verified_executable = check_docker_pin(executable_path, executable_sha256)
        arguments = [str(verified_executable), "compose", "-f", str(config_path.resolve()), "-p", project,
                     "--profile", "cm21", "config", "--format", "json"]
    else:
        raise ValueError("COMMAND_NOT_ALLOWED")
    primary = "NONE"
    process = None
    observers = []
    readers = []
    overflow = threading.Event()
    killed = False
    exit_code = None
    try:
        if time.monotonic() >= work_deadline:
            raise TimeoutError
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
            env=environment, shell=False)
        if time.monotonic() >= work_deadline:
            primary = "LATE_SPAWN"
        for pipe in [process.stdout, process.stderr]:
            observed = _ObservedStream(pipe, cap_bytes, overflow)
            reader = threading.Thread(target=observed.read, daemon=True)
            observers.append(observed)
            readers.append(reader)
            reader.start()
        while primary == "NONE":
            if overflow.is_set():
                primary = "OUTPUT_LIMIT"
                break
            remaining = work_deadline - time.monotonic()
            if remaining <= 0:
                primary = "TIMEOUT"
                break
            try:
                exit_code = process.wait(timeout=min(0.02, remaining))
                break
            except subprocess.TimeoutExpired:
                continue
    except TimeoutError:
        primary = "DEADLINE_EXCEEDED"
    except OSError:
        primary = "SPAWN_OS_ERROR"
    finally:
        if process is not None:
            if exit_code is None and process.poll() is None and time.monotonic() < deadline:
                try:
                    process.kill()
                    killed = True
                except OSError:
                    pass
            if exit_code is None:
                remaining = deadline - time.monotonic()
                if remaining > 0:
                    try:
                        exit_code = process.wait(timeout=remaining / 2)
                    except subprocess.TimeoutExpired:
                        pass
            for reader in readers:
                reader.join(timeout=max(0, deadline - time.monotonic()) / max(1, len(readers)))
            for observed, reader in zip(observers, readers):
                if not reader.is_alive():
                    try:
                        observed.pipe.close()
                        observed.closed = True
                    except OSError:
                        pass
    known_exit = process is None or exit_code is not None
    streams_complete = len(observers) == 2 and all(
        observed.eof and not observed.read_failed and observed.closed for observed in observers)
    if primary == "NONE":
        if overflow.is_set(): primary = "OUTPUT_LIMIT"
        elif not known_exit or not streams_complete: primary = "CLEANUP_UNKNOWN"
        elif exit_code != 0: primary = "NONZERO_EXIT"
    if primary == "NONE" and time.monotonic() >= deadline:
        primary = "DEADLINE_EXCEEDED"
    stream_receipts = [observed.receipt() for observed in observers]
    receipt = {
        "phase": kind,
        "primaryFailure": primary,
        "spawned": process is not None,
        "pid": process.pid if process is not None else None,
        "exitCode": exit_code,
        "exitConfirmed": process is not None and exit_code is not None,
        "killRequested": killed,
        "cleanupState": "CONFIRMED" if known_exit and (streams_complete or process is None) else "UNKNOWN",
        "readerThreadsAlive": sum(reader.is_alive() for reader in readers),
        "stdout": stream_receipts[0] if stream_receipts else None,
        "stderr": stream_receipts[1] if len(stream_receipts) == 2 else None,
        "safeFailureClasses": sorted({marker for observed in stream_receipts for marker in observed["safeFailureClasses"]}),
        "credentialsOrCommandTextArchived": False,
    }
    publication = _publish(Path(receipt_path), receipt)
    elapsed = time.monotonic() - started
    if not publication["saved"] and primary == "NONE": primary = "ARCHIVE_FAILED"
    if elapsed >= timeout_seconds and primary == "NONE": primary = "DEADLINE_EXCEEDED"
    if primary != "NONE":
        raise EvidenceFailure(primary, receipt, publication)
    return RecordedOutput(bytes(observers[0].prefix), bytes(observers[1].prefix), receipt, publication, elapsed)


def check_docker_pin(path: Path | None, expected_sha256: str | None) -> Path:
    """Require the caller's reviewed absolute Docker identity before any launch."""
    if path is None or expected_sha256 is None:
        raise ValueError("EXECUTABLE_PIN_REQUIRED")
    candidate = Path(path)
    if not candidate.is_absolute() or candidate.name.lower() not in {"docker", "docker.exe"}:
        raise ValueError("EXECUTABLE_PATH_REJECTED")
    if len(expected_sha256) != 64 or any(character not in "0123456789abcdef" for character in expected_sha256):
        raise ValueError("EXECUTABLE_HASH_REJECTED")
    with candidate.open("rb") as stream:
        observed = hashlib.file_digest(stream, "sha256").hexdigest()
    if observed != expected_sha256:
        raise ValueError("EXECUTABLE_HASH_MISMATCH")
    return candidate


def check_high_port(candidate: int) -> int:
    """Probe exactly one explicit loopback candidate; never choose a fallback."""
    if isinstance(candidate, bool) or not isinstance(candidate, int) or not 20000 <= candidate <= 60000:
        raise ValueError("PORT_OUTSIDE_APPROVED_RANGE")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            reservation.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        reservation.bind(("127.0.0.1", candidate))
    return candidate


def derive_bridge_config(base: dict, frozen_port: int) -> dict:
    """Keep original Redis settings and the named volume; change only topology.

    The base must already be rendered with the preparation's unique project,
    owner and port. Built-in bridge is shared infrastructure, never owned.
    """
    if isinstance(frozen_port, bool) or not isinstance(frozen_port, int) or not 20000 <= frozen_port <= 60000:
        raise ValueError("PORT_OUTSIDE_APPROVED_RANGE")
    service = copy.deepcopy(base["services"]["redis"])
    port = service["ports"]
    if len(port) != 1 or port[0]["host_ip"] != "127.0.0.1" or int(port[0]["published"]) != frozen_port or int(port[0]["target"]) != 6379:
        raise ValueError("REDIS_BINDING_CHANGED")
    if service.get("network_mode") or service.get("image") != "redis:7.0" or service.get("pull_policy") != "never":
        raise ValueError("REDIS_BASE_CHANGED")
    service.pop("networks", None)
    service["network_mode"] = "bridge"
    return {"name": base["name"], "services": {"redis": service},
            "volumes": {"redis_data": copy.deepcopy(base["volumes"]["redis_data"])}}

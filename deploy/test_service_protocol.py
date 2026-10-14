"""Exercise the real PowerShell probe against short-lived loopback fixtures.

Run: python deploy/test_service_protocol.py
Only Python's standard library and the user's PowerShell are required.
"""
from pathlib import Path
import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest

SHELL = os.environ.get('CLOUDMALL_TEST_POWERSHELL') or shutil.which('pwsh') or shutil.which('powershell')
HELPER = Path(__file__).with_name('service-protocol.ps1').resolve()


@unittest.skipUnless(SHELL, 'PowerShell is required')
class ServiceProtocolTest(unittest.TestCase):
    def probe(self, protocol, chunks, expected, *, stall=False):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        listener.settimeout(10)
        port = listener.getsockname()[1]
        finished = threading.Event()
        observed = []

        def serve():
            try:
                with listener.accept()[0] as connection:
                    connection.settimeout(3)
                    if protocol == 'redis':
                        request = b''
                        while len(request) < 14:
                            part = connection.recv(14 - len(request))
                            if not part:
                                break
                            request += part
                        observed.append(request)
                    if stall:
                        finished.wait(3)
                    else:
                        for chunk in chunks:
                            connection.sendall(chunk)
                            time.sleep(0.015)
            except (OSError, TimeoutError):
                pass

        worker = threading.Thread(target=serve, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory(prefix='cloudmall-protocol-') as directory:
                driver = Path(directory) / 'probe.ps1'
                driver.write_text(
                    "param([string]$Helper,[string]$Protocol,[int]$Port)\n"
                    ". $Helper\n"
                    "Test-CloudMallServiceProtocol -Protocol $Protocol -HostAddress 127.0.0.1 -Port $Port -TimeoutMilliseconds 300 | ConvertTo-Json -Compress\n",
                    encoding='utf-8-sig',
                )
                result = subprocess.run(
                    [SHELL, '-NoProfile', '-NonInteractive', '-File', str(driver),
                     '-Helper', str(HELPER), '-Protocol', protocol, '-Port', str(port)],
                    capture_output=True, timeout=15, encoding='utf-8', errors='replace',
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                payload = json.loads(result.stdout.lstrip('\ufeff'))
                self.assertEqual(payload['Reachable'], expected, payload)
                if protocol == 'redis':
                    self.assertEqual(observed, [b'*1\r\n$4\r\nPING\r\n'])
                if expected:
                    self.assertIsNone(payload['Error'])
                else:
                    self.assertIsNotNone(payload['Error'])
                return payload
        finally:
            finished.set()
            listener.close()
            worker.join(timeout=4)
            self.assertFalse(worker.is_alive(), 'fixture thread must be reaped')

    def test_mysql_fragmented_greeting(self):
        self.assertEqual(self.probe('mysql', [b'\x4a', b'\0\0', b'\0\x0a'], True)['Status'], 'MYSQL_GREETING')

    def test_mysql_eof(self):
        self.assertEqual(self.probe('mysql', [], False)['Error'], 'EndOfStreamException')

    def test_mysql_wrong_protocol(self):
        self.assertEqual(self.probe('mysql', [b'\x4a\0\0\0\x0b'], False)['Error'], 'InvalidDataException')

    def test_mysql_timeout(self):
        self.probe('mysql', [], False, stall=True)

    def test_redis_pong(self):
        self.assertEqual(self.probe('redis', [b'+PO', b'NG\r\n'], True)['Status'], 'PONG')

    def test_redis_noauth_is_protocol_only(self):
        self.assertEqual(self.probe('redis', [b'-NOAUTH Authentication required.\r\n'], True)['Status'], 'NOAUTH')

    def test_redis_wrong_reply(self):
        self.assertEqual(self.probe('redis', [b'HTTP/1.1 200 OK\r\n'], False)['Error'], 'InvalidDataException')

    def test_redis_eof(self):
        self.assertEqual(self.probe('redis', [], False)['Error'], 'EndOfStreamException')


if __name__ == '__main__':
    unittest.main(verbosity=2)

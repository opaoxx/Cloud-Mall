"""Loopback-only HTTP fault proxy for Order-to-Stock integration verification.

POST /__fault sets {path, mode, delay_ms, remaining}; mode is pass,
reject_before, delay_after, or drop_after. POST /__fault with mode=pass clears
the fault. GET /__stats reports forwarding counts without request payloads.
"""

import argparse
import http.client
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class ProxyState:
    def __init__(self, upstream_port):
        self.upstream_port = upstream_port
        self.lock = threading.Lock()
        self.fault = {"path": "", "mode": "pass", "delay_ms": 0, "remaining": 0}
        self.requests = []

    def consume_fault(self, path):
        with self.lock:
            fault = dict(self.fault)
            if path != fault["path"] or fault["mode"] == "pass" or fault["remaining"] == 0:
                return {"mode": "pass", "delay_ms": 0}
            if fault["remaining"] > 0:
                self.fault["remaining"] -= 1
            return fault


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self):
        state = self.server.state
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/__fault" and self.command == "POST":
            configuration = json.loads(body)
            if configuration.get("mode") not in {"pass", "reject_before", "delay_after", "drop_after"}:
                self.reply(400, {"error": "unsupported mode"})
                return
            if not 0 <= int(configuration.get("delay_ms", 0)) <= 30000:
                self.reply(400, {"error": "delay must be between 0 and 30000 ms"})
                return
            with state.lock:
                state.fault = {
                    "path": configuration.get("path", ""),
                    "mode": configuration["mode"],
                    "delay_ms": int(configuration.get("delay_ms", 0)),
                    "remaining": int(configuration.get("remaining", -1)),
                }
            self.reply(200, {"configured": True})
            return
        if self.path == "/__stats" and self.command == "GET":
            with state.lock:
                snapshot = list(state.requests)
            self.reply(200, {"requests": snapshot})
            return
        started_at = time.monotonic()
        fault = state.consume_fault(self.path)
        observation = {"method": self.command, "path": self.path,
                       "mode": fault["mode"], "forwarded": False}
        connection = None
        try:
            if fault["mode"] == "reject_before":
                observation["status"] = 503
                self.reply(503, {"code": "GATEWAY_SERVICE_UNAVAILABLE",
                                 "message": "controlled Stock fault", "data": None})
                return
            connection = http.client.HTTPConnection("127.0.0.1", state.upstream_port, timeout=20)
            headers = {name: value for name, value in self.headers.items()
                       if name.lower() not in {"host", "connection", "transfer-encoding"}}
            connection.request(self.command, self.path, body, headers)
            observation["forwarded"] = True
            response = connection.getresponse()
            response_body = response.read()
            observation["status"] = response.status
            if fault["mode"] == "drop_after":
                self.close_connection = True
                return
            if fault["mode"] == "delay_after":
                time.sleep(fault["delay_ms"] / 1000)
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in {"connection", "transfer-encoding", "content-length"}:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            observation["client_disconnected"] = True
        except Exception as failure:
            observation["error"] = type(failure).__name__
            try:
                self.reply(503, {"code": "GATEWAY_SERVICE_UNAVAILABLE", "message": "proxy upstream unavailable"})
            except OSError:
                pass
        finally:
            if connection is not None:
                connection.close()
            observation["elapsed_ms"] = round((time.monotonic() - started_at) * 1000)
            with state.lock:
                state.requests.append(observation)
            print(json.dumps(observation), flush=True)

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request
    do_DELETE = handle_request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18095)
    parser.add_argument("--upstream-port", type=int, default=18085)
    arguments = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", arguments.port), Handler)
    server.state = ProxyState(arguments.upstream_port)
    server.serve_forever()


if __name__ == "__main__":
    main()

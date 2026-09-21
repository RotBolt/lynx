#!/usr/bin/env python3
"""Tiny deterministic HTTP/1.1 + WebSocket fixture server for the emulator."""

import base64
import hashlib
import http.server
import json
import socketserver
import struct


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/ws":
            self.websocket()
            return
        payload = json.dumps({"fixture": "lynx-dummyapp", "ok": True}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)

    def websocket(self):
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        header = self.rfile.read(2)
        if len(header) != 2:
            return
        length = header[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self.rfile.read(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self.rfile.read(8))[0]
        mask = self.rfile.read(4) if header[1] & 0x80 else b""
        payload = bytearray(self.rfile.read(length))
        if mask:
            for i in range(len(payload)):
                payload[i] ^= mask[i % 4]
        response = bytes([0x81, len(payload)]) + payload
        self.connection.sendall(response)

    def log_message(self, fmt, *args):
        print(fmt % args, flush=True)


if __name__ == "__main__":
    with socketserver.ThreadingTCPServer(("0.0.0.0", 8080), Handler) as server:
        print("dummyapp test server listening on 0.0.0.0:8080", flush=True)
        server.serve_forever()

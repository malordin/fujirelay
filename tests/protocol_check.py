#!/usr/bin/env python3
"""Run device-side SQLite/cache/HTTP checks against an isolated Immich contract fixture.

Requires an installed DEBUG APK and one authorized ADB device. Uses synthetic
bytes only; never reads photos, the production database, or Immich credentials.
"""
import base64
import hashlib
import json
import subprocess
import threading
import uuid
import time
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ASSETS = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, value, code=200):
        data = json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        route, path = self.path.lstrip("/").split("/api", 1)
        if path == "/server/version":
            return self.reply({"major": 2 if route == "v2" else 3, "minor": 0, "patch": 0})
        if path == "/users/me":
            return self.reply({"id": "test-owner"})
        asset = next((a for (r, _), a in ASSETS.items() if r == route and a["id"] == path.split("/")[-1]), None)
        if not asset:
            return self.reply({}, 404)
        if route == "server-error":
            return self.reply({"error": "test error"}, 503)
        if route == "unauthorized":
            return self.reply({}, 403)
        if route == "redirect":
            self.send_response(302)
            self.send_header("Location", "/should-never-follow")
            self.end_headers()
            return
        if route == "slow":
            time.sleep(10)
        result = {**asset, "ownerId": "test-owner", "isTrashed": route == "trashed", "isOffline": route == "offline"}
        if route == "wrong-hash":
            result["checksum"] = "not-the-same-hash"
        if route == "missing-hash":
            del result["checksum"]
        if route == "wrong-owner":
            result["ownerId"] = "another-owner"
        return self.reply(result)

    def do_POST(self):
        route, path = self.path.lstrip("/").split("/api", 1)
        length = int(self.headers.get("Content-Length", 0))
        if length > 1024 * 1024 or self.headers.get("x-api-key") != "synthetic-test-key":
            return self.reply({}, 400)
        body = self.rfile.read(length)
        if path == "/assets/bulk-upload-check":
            item = json.loads(body)["assets"][0]
            asset = ASSETS.get((route, item["checksum"]))
            return self.reply({"results": [{"id": item["id"], "action": "reject" if asset else "accept",
                **({"reason": "duplicate", "assetId": asset["id"]} if asset else {})}]})
        if path != "/assets":
            return self.reply({}, 404)
        message = BytesParser(policy=default).parsebytes(b"Content-Type: " + self.headers["Content-Type"].encode() + b"\r\n\r\n" + body)
        fields = {part.get_param("name", header="content-disposition"): part.get_payload(decode=True) for part in message.iter_parts()}
        if route == "v2" and not {"deviceId", "deviceAssetId"} <= fields.keys():
            return self.reply({}, 400)
        if route != "v2" and ({"deviceId", "deviceAssetId"} & fields.keys()):
            return self.reply({}, 400)
        checksum = base64.b64encode(hashlib.sha1(fields["assetData"]).digest()).decode()
        if checksum != self.headers["x-immich-checksum"]:
            return self.reply({}, 400)
        asset = ASSETS.setdefault((route, checksum), {"id": str(uuid.uuid4()), "checksum": checksum})
        if route == "lost-response":
            self.close_connection = True
            self.connection.shutdown(2)
            self.connection.close()
            return
        if route == "malformed":
            return self.reply({})
        return self.reply({"id": asset["id"], "status": "unknown" if route == "bad-status" else "created"}, 201)


def main():
    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        port = server.server_port
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        subprocess.run(["adb", "reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
        try:
            result = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-e", "base", f"http://127.0.0.1:{port}",
                "dev.malordin.fuji.usb/dev.malordin.fuji.ProtocolCheck"], capture_output=True, text=True, timeout=120)
            print(result.stdout, end="")
            assert "PASS " in result.stdout and "FAIL" not in result.stdout, result.stdout + result.stderr
        finally:
            subprocess.run(["adb", "reverse", "--remove", f"tcp:{port}"], check=True)
            server.shutdown()


if __name__ == "__main__":
    main()

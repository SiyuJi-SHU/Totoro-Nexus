"""Small acceptance client. Reads local bootstrap credentials without printing or persisting them."""
import json
import os
import urllib.request
import urllib.error
import urllib.parse
import http.cookiejar
from pathlib import Path


class PlatformClient:
    def __init__(self, base="http://localhost:9900", username="admin", password=None):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        if password is None:
            password = os.getenv("PLATFORM_ADMIN_PASSWORD") or Path("uploads/.platform/admin-initial-password.txt").read_text().strip()
        self.csrf = self.call("GET", "/api/auth/csrf")
        body = urllib.parse.urlencode({"username": username, "password": password}).encode()
        self.call("POST", "/api/auth/login", body, "application/x-www-form-urlencoded")
        self.csrf = self.call("GET", "/api/auth/csrf")

    def call(self, method, path, data=None, content_type="application/json", timeout=330):
        headers = {}
        if self.csrf and method not in ("GET", "HEAD"):
            headers[self.csrf["headerName"]] = self.csrf["token"]
        if data is not None:
            if not isinstance(data, bytes):
                data = json.dumps(data, ensure_ascii=False).encode()
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        with self.opener.open(request, timeout=timeout) as response:
            body = response.read()
            return json.loads(body) if body else None

    def upload(self, path, file_path, fields):
        import uuid
        boundary = "acceptance-" + uuid.uuid4().hex
        chunks = []
        for key, value in fields.items():
            chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        file = Path(file_path)
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file.name}"\r\nContent-Type: text/plain\r\n\r\n'.encode())
        chunks.extend([file.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()])
        return self.call("POST", path, b"".join(chunks), f"multipart/form-data; boundary={boundary}")

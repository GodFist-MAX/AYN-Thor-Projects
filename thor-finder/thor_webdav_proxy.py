#!/usr/bin/env python3
"""WebDAV proxy between Finder and the rclone server on the AYN Thor.

macOS keeps extended attributes and Finder view settings on WebDAV volumes in AppleDouble
"._name" files and ".DS_Store" files. This proxy stores those in a folder on the Mac, so they
never reach the Thor, and passes every other request through to rclone unchanged.
"""
import argparse
import email.utils
import http.client
import http.server
import os
import re
import shutil
import time
import urllib.parse
import uuid
from xml.sax.saxutils import escape

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailer", "transfer-encoding", "upgrade", "expect"}
CHUNK = 1 << 20
BACKEND_IDLE_LIMIT = 20  # seconds; rclone may have closed an idle keep-alive connection by then


def is_mac_metadata(url_path):
    name = urllib.parse.unquote(url_path).rstrip("/").rsplit("/", 1)[-1]
    return name.startswith("._") or name == ".DS_Store"


def etag(st):
    return '"%x%x"' % (int(st.st_mtime * 1e9), st.st_size)


def http_date(timestamp):
    return email.utils.formatdate(timestamp, usegmt=True)


class BackendDown(Exception):
    pass


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "thor-webdav-proxy"
    backend = ("127.0.0.1", 18765)
    metadata_dir = "/private/tmp/thor-mac-metadata"
    conn = None
    conn_used = 0.0

    def log_message(self, fmt, *args):
        pass

    def handle_request(self):
        path = urllib.parse.urlsplit(self.path).path
        try:
            if self.command != "OPTIONS" and is_mac_metadata(path):
                self.metadata_request(path)
            else:
                self.forward()
        except BackendDown:
            # Drop the connection instead of answering, so webdavfs treats the Thor as gone and
            # unmounts (mount_webdav -S) rather than leaving Finder stuck on error replies.
            self.close_connection = True
        except ValueError:
            self.reply(403)

    do_GET = do_HEAD = do_PUT = do_POST = do_DELETE = do_OPTIONS = handle_request
    do_PROPFIND = do_PROPPATCH = do_MKCOL = do_COPY = do_MOVE = do_LOCK = do_UNLOCK = handle_request

    def body_chunks(self):
        """Yield the request body, decoding chunked transfer encoding."""
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                        pass
                    return
                yield from self.read_exactly(size)
                self.rfile.readline()
        else:
            yield from self.read_exactly(int(self.headers.get("Content-Length") or 0))

    def read_exactly(self, remaining):
        while remaining:
            data = self.rfile.read(min(remaining, CHUNK))
            if not data:
                raise ConnectionError("client closed the connection mid-body")
            remaining -= len(data)
            yield data

    def backend_connection(self, fresh=False):
        if fresh or self.conn is None or time.monotonic() - self.conn_used > BACKEND_IDLE_LIMIT:
            if self.conn is not None:
                self.conn.close()
            self.conn = http.client.HTTPConnection(*self.backend, timeout=120)
        return self.conn

    def send_to_backend(self, conn, headers, body, chunked):
        conn.putrequest(self.command, self.path, skip_host=True, skip_accept_encoding=True)
        for key, value in headers:
            conn.putheader(key, value)
        if chunked:
            conn.putheader("Transfer-Encoding", "chunked")
        conn.endheaders()
        if body is not None:
            for data in body:
                conn.send(b"%x\r\n%s\r\n" % (len(data), data) if chunked else data)
            if chunked:
                conn.send(b"0\r\n\r\n")
        return conn.getresponse()

    def forward(self):
        headers = [(k, v) for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP]
        chunked = self.headers.get("Transfer-Encoding", "").lower() == "chunked"
        has_body = chunked or int(self.headers.get("Content-Length") or 0) > 0
        try:
            try:
                resp = self.send_to_backend(self.backend_connection(), headers,
                                            self.body_chunks() if has_body else None, chunked)
            except (http.client.RemoteDisconnected, BrokenPipeError, ConnectionResetError):
                if has_body:
                    raise
                resp = self.send_to_backend(self.backend_connection(fresh=True), headers, None, False)
        except (OSError, http.client.HTTPException) as exc:
            if self.conn is not None:
                self.conn.close()
                self.conn = None
            raise BackendDown() from exc

        no_body = self.command == "HEAD" or resp.status in (204, 304)
        length = resp.getheader("Content-Length")
        self.send_response_only(resp.status, resp.reason)
        for key, value in resp.getheaders():
            if key.lower() not in HOP_BY_HOP:
                self.send_header(key, value)
        chunked_out = not no_body and length is None
        if chunked_out:
            self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            if no_body:
                resp.read()
            while not no_body:
                data = resp.read(CHUNK)
                if not data:
                    break
                self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data) if chunked_out else data)
            if chunked_out:
                self.wfile.write(b"0\r\n\r\n")
        finally:
            if resp.will_close or not resp.isclosed():
                self.conn.close()
                self.conn = None
            else:
                self.conn_used = time.monotonic()

    def local_path(self, url_path):
        path = os.path.normpath(os.path.join(self.metadata_dir, urllib.parse.unquote(url_path).lstrip("/")))
        if not path.startswith(self.metadata_dir + os.sep):
            raise ValueError("path escapes the metadata folder: %r" % url_path)
        return path

    def reply(self, status, body=b"", headers=(), length=None):
        self.send_response(status)
        for key, value in headers:
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body) if length is None else length))
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def metadata_request(self, url_path):
        path = self.local_path(url_path)
        method = self.command
        if method == "PUT":
            existed = os.path.exists(path)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                for data in self.body_chunks():
                    f.write(data)
            return self.reply(204 if existed else 201)

        request_body = b"".join(self.body_chunks())
        exists = os.path.isfile(path)
        if method in ("GET", "HEAD"):
            if not exists:
                return self.reply(404)
            st = os.stat(path)
            with open(path, "rb") as f:
                data = f.read() if method == "GET" else b""
            return self.reply(200, data, [("Content-Type", "application/octet-stream"),
                                          ("Last-Modified", http_date(st.st_mtime)), ("ETag", etag(st))],
                              length=st.st_size)
        if method == "PROPFIND":
            if not exists:
                return self.reply(404)
            return self.reply(207, self.propfind_xml(url_path, os.stat(path)),
                              [("Content-Type", "application/xml; charset=utf-8")])
        if method == "DELETE":
            if not exists:
                return self.reply(404)
            os.remove(path)
            return self.reply(204)
        if method == "LOCK":
            refresh = re.search(r"\(<([^>]+)>\)", self.headers.get("If", ""))
            token = refresh.group(1) if refresh else "opaquelocktoken:%s" % uuid.uuid4()
            if not exists:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                open(path, "ab").close()
            owner = re.search(rb"<(?:\w+:)?owner\b[^>]*>(.*?)</(?:\w+:)?owner>", request_body, re.S)
            return self.reply(200 if exists else 201, self.lock_xml(url_path, token, owner),
                              [("Content-Type", "application/xml; charset=utf-8"), ("Lock-Token", "<%s>" % token)])
        if method == "UNLOCK":
            return self.reply(204)
        if method in ("MOVE", "COPY"):
            dest_url = urllib.parse.urlsplit(self.headers.get("Destination", "")).path
            if not is_mac_metadata(dest_url):
                return self.reply(403)
            if not exists:
                return self.reply(404)
            dest = self.local_path(dest_url)
            dest_existed = os.path.exists(dest)
            if dest_existed and self.headers.get("Overwrite", "T").upper() == "F":
                return self.reply(412)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            (shutil.copy2 if method == "COPY" else os.replace)(path, dest)
            return self.reply(204 if dest_existed else 201)
        if method == "PROPPATCH":
            if not exists:
                return self.reply(404)
            return self.reply(207, self.multistatus(url_path, ""), [("Content-Type", "application/xml; charset=utf-8")])
        return self.reply(405)

    @staticmethod
    def multistatus(url_path, props):
        return ('<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:"><D:response>'
                "<D:href>%s</D:href><D:propstat><D:prop>%s</D:prop><D:status>HTTP/1.1 200 OK</D:status>"
                "</D:propstat></D:response></D:multistatus>" % (escape(url_path), props)).encode()

    def propfind_xml(self, url_path, st):
        name = urllib.parse.unquote(url_path).rsplit("/", 1)[-1]
        return self.multistatus(url_path, (
            "<D:resourcetype></D:resourcetype><D:getcontentlength>%d</D:getcontentlength>"
            "<D:getlastmodified>%s</D:getlastmodified><D:getcontenttype>application/octet-stream</D:getcontenttype>"
            "<D:getetag>%s</D:getetag><D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>"
            "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock><D:displayname>%s</D:displayname>"
        ) % (st.st_size, http_date(st.st_mtime), escape(etag(st)), escape(name)))

    def lock_xml(self, url_path, token, owner):
        timeout = self.headers.get("Timeout", "Second-600").split(",")[0].strip()
        return ('<?xml version="1.0" encoding="utf-8"?><D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock>'
                "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>"
                "<D:depth>0</D:depth>%s<D:timeout>%s</D:timeout><D:locktoken><D:href>%s</D:href></D:locktoken>"
                "<D:lockroot><D:href>%s</D:href></D:lockroot></D:activelock></D:lockdiscovery></D:prop>" % (
                    "<D:owner>%s</D:owner>" % owner.group(1).decode() if owner else "",
                    escape(timeout), escape(token), escape(url_path))).encode()


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def daemonize(pid_file, log_file):
    """Detach into a new session so the proxy outlives the shell (and process group) that started it."""
    if os.fork():
        os._exit(0)
    os.setsid()
    if os.fork():
        os._exit(0)
    devnull = os.open(os.devnull, os.O_RDWR)
    log = os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
    os.dup2(devnull, 0)
    os.dup2(log, 1)
    os.dup2(log, 2)
    with open(pid_file, "w") as f:
        f.write("%d\n" % os.getpid())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--backend-port", type=int, default=18765)
    parser.add_argument("--metadata-dir", default="/private/tmp/thor-mac-metadata")
    parser.add_argument("--daemon", metavar="PID_FILE", help="run in the background, writing the PID here")
    parser.add_argument("--log", default=os.devnull, help="log file when running with --daemon")
    args = parser.parse_args()
    Handler.backend = ("127.0.0.1", args.backend_port)
    Handler.metadata_dir = os.path.realpath(args.metadata_dir)
    os.makedirs(Handler.metadata_dir, exist_ok=True)
    server = Server(("127.0.0.1", args.port), Handler)
    if args.daemon:
        daemonize(os.path.abspath(args.daemon), os.path.abspath(args.log))
    server.serve_forever()


if __name__ == "__main__":
    main()

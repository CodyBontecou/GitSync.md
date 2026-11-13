"""Owned loopback HTTPS Git server with a pause on either side of receive-pack.
No user account, credential, external repository or global Git config is used.
"""
import base64
import gzip
import http.server
import json
import os
import pathlib
import ssl
import subprocess
import tempfile
import threading
import urllib.parse


class CallbackGitFixture:
    def __init__(self, mode):
        assert mode in ('before-remote', 'after-remote', 'after-result')
        self.mode = mode
        self.root = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-callback-git-', dir='/tmp'))
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null')
        self.lock = threading.Lock()
        self.held = threading.Event()
        self.released = threading.Event()
        self.stage = 'ready'
        self.receives = self.applied = self.dropped = self.authorization_headers = 0
        self.git('init', '--bare', '--initial-branch=main', str(self.root / 'repo.git'))
        self.git('--git-dir=' + str(self.root / 'repo.git'), 'config', 'http.receivepack', 'true')
        self.git('init', '--initial-branch=main', str(self.root / 'seed'))
        (self.root / 'seed/note.md').write_text('Initial callback interruption fixture\n')
        self.git('-C', str(self.root / 'seed'), 'add', '.')
        self.git('-C', str(self.root / 'seed'), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'Initial')
        self.git('-C', str(self.root / 'seed'), 'push', str(self.root / 'repo.git'), 'main')
        self.initial = self.head()
        subprocess.run(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
            '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', str(self.root / 'tls-key.pem'),
            '-out', str(self.root / 'tls-cert.pem')], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'
            def log_message(self, *args):
                pass
            def respond(self, status, body, headers=()):
                self.send_response(status)
                for key, value in headers:
                    if key.lower() not in ('status', 'content-length', 'transfer-encoding', 'connection'):
                        self.send_header(key, value)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                    pass  # The fixture deliberately allows the Android client to die before acknowledgement.
            def read_body(self):
                if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                    result = bytearray()
                    while True:
                        size = int(self.rfile.readline(128).split(b';')[0].strip(), 16)
                        if size == 0:
                            while self.rfile.readline(8192).strip():
                                pass
                            break
                        if len(result) + size > 2 * 1024 * 1024:
                            raise ValueError('Fixture body too large')
                        result.extend(self.rfile.read(size))
                        assert self.rfile.read(2) == b'\r\n'
                    return bytes(result)
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 <= size <= 2 * 1024 * 1024:
                    raise ValueError('Fixture body too large')
                return self.rfile.read(size)
            def do_GET(self):
                self.serve()
            def do_POST(self):
                self.serve()
            def serve(self):
                parsed = urllib.parse.urlsplit(self.path)
                if parsed.path == '/fixture/state' and self.command == 'GET':
                    return self.respond(200, json.dumps(fixture.state()).encode(), [('Content-Type', 'application/json')])
                if parsed.path not in ('/repo.git/info/refs', '/repo.git/git-upload-pack', '/repo.git/git-receive-pack'):
                    return self.respond(404, b'')
                if self.headers.get('Authorization') is not None:
                    with fixture.lock:
                        fixture.authorization_headers += 1
                    return self.respond(403, b'')
                body = self.read_body()
                if self.headers.get('Content-Encoding', '').lower() == 'gzip':
                    body = gzip.decompress(body)
                    assert len(body) <= 2 * 1024 * 1024
                receive = parsed.path.endswith('/git-receive-pack') and self.command == 'POST'
                first = False
                if receive:
                    with fixture.lock:
                        fixture.receives += 1
                        first = fixture.receives == 1
                if receive and first and fixture.mode == 'before-remote':
                    with fixture.lock:
                        fixture.stage = 'held-before-remote'
                    fixture.held.set()
                    assert fixture.released.wait(120), 'Fixture release timed out'
                    with fixture.lock:
                        fixture.stage = 'dropped-before-remote'
                        fixture.dropped += 1
                    return self.respond(503, b'')
                env = dict(fixture.env, GIT_PROJECT_ROOT=str(fixture.root), GIT_HTTP_EXPORT_ALL='1',
                    REQUEST_METHOD=self.command, PATH_INFO=parsed.path, QUERY_STRING=parsed.query,
                    CONTENT_TYPE=self.headers.get('Content-Type', ''), CONTENT_LENGTH=str(len(body)), REMOTE_USER='fixture')
                if self.headers.get('Git-Protocol'):
                    env['HTTP_GIT_PROTOCOL'] = self.headers['Git-Protocol']
                process = subprocess.run(['/usr/bin/git', 'http-backend'], input=body, capture_output=True, env=env, timeout=15, check=True)
                output = process.stdout
                if b'\r\n\r\n' in output:
                    head, data = output.split(b'\r\n\r\n', 1)
                else:
                    head, data = output.split(b'\n\n', 1)
                headers = [tuple(line.decode().split(':', 1)) for line in head.splitlines()]
                status = next((int(value.strip().split()[0]) for key, value in headers if key.lower() == 'status'), 200)
                if receive:
                    with fixture.lock:
                        fixture.applied += 1
                        fixture.stage = 'published'
                if receive and first and fixture.mode == 'after-remote':
                    with fixture.lock:
                        fixture.stage = 'held-after-remote'
                    fixture.held.set()
                    assert fixture.released.wait(120), 'Fixture release timed out'
                return self.respond(status, data, [(key, value.strip()) for key, value in headers])

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(str(self.root / 'tls-cert.pem'), str(self.root / 'tls-key.pem'))
        self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.port = self.server.server_port
        self.url = f'https://127.0.0.1:{self.port}/repo.git'
        self.certificate = base64.b64encode(ssl.PEM_cert_to_DER_cert((self.root / 'tls-cert.pem').read_text())).decode()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def git(self, *args):
        return subprocess.check_output(['/usr/bin/git', *args], env=self.env, stderr=subprocess.DEVNULL, timeout=15).decode().strip()

    def head(self):
        return self.git('--git-dir=' + str(self.root / 'repo.git'), 'rev-parse', 'refs/heads/main')

    def state(self):
        with self.lock:
            return dict(mode=self.mode, stage=self.stage, receives=self.receives, applied=self.applied,
                dropped=self.dropped, authorization_headers=self.authorization_headers, head=self.head(), initial=self.initial)

    def close(self):
        self.released.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        assert not self.thread.is_alive()

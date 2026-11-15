"""Private-LAN, synthetic QA-only x-callback receiver. No repository/file serving."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from pathlib import Path
import html
import json

LEDGER = Path('/tmp/pr44-callback-ledger.jsonl')
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == '/baseline':
            content = b'<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Owned QA callback baseline</title></head><body><h1>QA callback awaiting a new result</h1></body></html>'
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return
        if url.path not in ('/error', '/success'):
            self.send_error(404)
            return
        params = parse_qs(url.query, keep_blank_values=True)
        entry = {'path': url.path, 'params': params}
        with LEDGER.open('a') as file:
            file.write(json.dumps(entry, ensure_ascii=False) + '\n')
        rows = [f'<h1>QA callback received: {url.path[1:]}</h1>']
        for key in ('action', 'status', 'updated', 'message'):
            rows.append(f'<p>{key}: {html.escape(str(params.get(key)))}</p>')
        rows.append(f'<p>sha present: {"yes" if "sha" in params else "no"}</p>')
        content = ('<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Owned QA callback receiver</title></head><body>' + ''.join(rows) + '</body></html>').encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)
ThreadingHTTPServer(('192.168.1.252', 8771), Handler).serve_forever()

#!/usr/bin/env python3
"""Serve only the current sanitized image status, bound to loopback."""
import http.server,pathlib,urllib.parse
from image_build_config import CONFIG,REPORT
FILES={
    '/':REPORT,
    '/pixel9-image-build-state.json':REPORT,
}
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        name=FILES.get(urllib.parse.urlsplit(self.path).path)
        if not name or not name.is_file():self.send_error(404);return
        data=name.read_bytes()
        self.send_response(200)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        if self.command!='HEAD':self.wfile.write(data)
    do_HEAD=do_GET
    def log_message(self,*args):pass
if __name__=='__main__':http.server.ThreadingHTTPServer(('127.0.0.1',CONFIG['reportPort']),Handler).serve_forever()

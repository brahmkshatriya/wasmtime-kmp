import socket
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

SMALL = b'x' * 1024
LARGE = b'y' * 65536

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def setup(self):
        super().setup()
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    def do_GET(self):
        q = parse_qs(urlparse(self.path).query)
        size = int(q.get('size', ['1024'])[0])
        body = LARGE if size == 65536 else SMALL
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'keep-alive')
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *args):
        pass

server = ThreadingHTTPServer(('127.0.0.1', 18080), Handler)
server.daemon_threads = True
server.serve_forever()

import http.server, http.client, pathlib, re
s=(pathlib.Path.home()/'.codex-lane/experts/haipro-codex/config.toml').read_text().split('[model_providers.magpie_bedrock]')[1].split('[features]')[0]
token=re.search(r'experimental_bearer_token\s*=\s*"([^"]+)"',s).group(1)
actor=re.search(r'x-openai-actor-authorization\s*=\s*"([^"]+)"',s).group(1)
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*a): pass
 def forward(self):
  size=int(self.headers.get('Content-Length','0')); body=self.rfile.read(size) if size else None
  headers={k:v for k,v in self.headers.items() if k.lower() not in ('authorization','host','connection','x-openai-actor-authorization')}
  headers.update({'Authorization':'Bearer '+token,'x-openai-actor-authorization':actor,'Connection':'close'})
  c=http.client.HTTPConnection('127.0.0.1',13427,timeout=300)
  try:
   c.request(self.command,self.path,body,headers);r=c.getresponse();self.send_response(r.status)
   for k,v in r.getheaders():
    if k.lower() not in ('transfer-encoding','connection','content-length'):self.send_header(k,v)
   self.send_header('Connection','close');self.end_headers();self.close_connection=True
   while True:
    chunk=r.read1(8192)
    if not chunk:break
    self.wfile.write(chunk);self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError):pass
  finally:c.close()
 def do_GET(self):self.forward()
 def do_POST(self):self.forward()
http.server.ThreadingHTTPServer(('127.0.0.1',13428),Handler).serve_forever()

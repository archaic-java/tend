"""Controlled CPU protocol fixture, NOT llama.cpp or ROCm inference.
Contracts reviewed against llama.cpp b11277 server README and server-models.cpp.
"""
import configparser
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

presets = configparser.ConfigParser(interpolation=None)
presets.read('/etc/llama/models.ini')
assert set(presets.sections()) == {'*', 'mimo', 'qwen36'}
cache = Path('/var/cache/llama')
(cache / 'permissions-proven').write_text('uid-1000-writable\n')
loaded = None
loads = 0

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body, kind='application/json'):
        payload = json.dumps(body).encode() if kind == 'application/json' else body.encode()
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        uri = urlparse(self.path)
        args = parse_qs(uri.query)
        if uri.path == '/health':
            return self.reply(200, {'status': 'ok', 'coverage': 'controlled-cpu-protocol'})
        if uri.path in ('/models', '/v1/models'):
            return self.reply(200, {'object': 'list', 'data': [
                {'id': model, 'object': 'model', 'status': {'value': 'loaded' if loaded == model else 'unloaded'}}
                for model in ('mimo', 'qwen36')]})
        if uri.path == '/fixture':
            return self.reply(200, {'loads': loads, 'loaded': loaded, 'uid': __import__('os').getuid()})
        if uri.path == '/metrics':
            model = args.get('model', [''])[0]
            if args.get('autoload') != ['false']:
                return self.reply(400, {'error': 'Monitoring must explicitly disable autoload'})
            if loaded != model:
                return self.reply(503, {'error': 'Model is not loaded'})
            return self.reply(200, 'llamacpp:tokens_predicted_total 1\n', 'text/plain')
        self.reply(404, {'error': 'unknown route'})

    def do_POST(self):
        global loaded, loads
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        model = body.get('model')
        if model not in ('mimo', 'qwen36'):
            return self.reply(404, {'error': 'unknown model'})
        if self.path == '/models/unload':
            loaded = None
            return self.reply(200, {'success': True})
        if self.path not in ('/models/load', '/v1/chat/completions'):
            return self.reply(404, {'error': 'unknown route'})
        if loaded != model:
            loaded = model
            loads += 1
            (cache / (model + '.synthetic-cache')).write_text('protocol-only; not a model download\n')
        if self.path == '/models/load':
            return self.reply(200, {'success': True})
        if body.get('stream'):
            chunk = {'id': 'synthetic-ci', 'object': 'chat.completion.chunk', 'model': model,
                     'choices': [{'index': 0, 'delta': {'content': 'synthetic CPU protocol response'}, 'finish_reason': None}]}
            return self.reply(200, 'data: ' + json.dumps(chunk) + '\n\ndata: [DONE]\n\n', 'text/event-stream')
        self.reply(200, {'id': 'synthetic-ci', 'object': 'chat.completion', 'model': model,
                        'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': 'synthetic CPU protocol response'}, 'finish_reason': 'stop'}]})

HTTPServer(('0.0.0.0', 8080), Handler).serve_forever()

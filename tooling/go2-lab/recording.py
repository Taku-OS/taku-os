"""Bounded local JSONL recording. Replay returns sensor events, never commands."""
import base64
import json
from pathlib import Path
import queue
import threading
import time
import uuid

MAX_BYTES = 64*1024*1024
MAX_LINE = 8*1024*1024


class Recorder:
    def __init__(self, root):
        self.root = Path(root)
        self.active = False
        self.error = None
        self.count = self.size = 0
        self.path = self.last_path = None
        self.worker = None
        self.items = None

    def snapshot(self):
        return {'active': self.active, 'error': self.error, 'events': self.count,
                'bytes': self.size, 'session': self.path.stem if self.path else None,
                'replayAvailable': self.last_path is not None and not self.active}

    def start(self, source):
        if self.active or (self.worker and self.worker.is_alive()): raise RuntimeError('RECORDING_BUSY')
        self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root/(uuid.uuid4().hex+'.jsonl')
        self.error = None; self.count = self.size = 0
        self.started = time.monotonic()
        self.items = queue.Queue(maxsize=64)
        self.active = True
        self.worker = threading.Thread(target=self._write, daemon=True)
        self.worker.start()
        self.emit('header', {'schema':'taku-go2-recording-v1','source':source,
                             'clock':'monotonic-seconds-relative', 'wallTime':time.time()})

    def emit(self, kind, payload):
        if not self.active: return
        try: self.items.put_nowait({'t':time.monotonic()-self.started, 'kind':kind, 'payload':payload})
        except queue.Full:
            self.error = 'RECORDING_BACKPRESSURE'; self.active = False

    def _write(self):
        def encode(value):
            if isinstance(value, bytes): return {'encoding':'base64','data':base64.b64encode(value).decode()}
            raise TypeError('UNSUPPORTED_RECORD_VALUE')
        try:
            with self.path.open('x', encoding='utf-8') as output:
                while True:
                    item = self.items.get()
                    if item is None: break
                    if self.error: continue
                    line = json.dumps(item, default=encode, allow_nan=False, separators=(',',':'))+'\n'
                    size = len(line.encode())
                    if size > MAX_LINE or self.size+size > MAX_BYTES:
                        self.error = 'RECORDING_LIMIT'; self.active = False; continue
                    output.write(line); output.flush()
                    self.size += size; self.count += 1
        except Exception:
            self.error = 'RECORDING_WRITE_FAILED'; self.active = False

    def stop(self):
        self.active = False
        if self.worker and self.worker.is_alive():
            try: self.items.put(None, timeout=2)
            except queue.Full:
                self.error = 'RECORDING_CLOSE_TIMEOUT'
                return self.snapshot()
            self.worker.join(timeout=3)
            if self.worker.is_alive(): self.error='RECORDING_CLOSE_TIMEOUT'
        if self.path and self.error is None: self.last_path = self.path
        return self.snapshot()

    def load_latest(self):
        if self.active or not self.last_path: raise ValueError('NO_COMPLETED_RECORDING')
        if self.last_path.stat().st_size > MAX_BYTES: raise ValueError('RECORDING_TOO_LARGE')
        events = []
        previous = -1
        def invalid(_): raise ValueError('NON_FINITE_RECORDING')
        with self.last_path.open(encoding='utf-8') as file:
            while True:
                line = file.readline(MAX_LINE+1)
                if not line: break
                if len(line) > MAX_LINE: raise ValueError('RECORD_LINE_TOO_LARGE')
                event = json.loads(line, parse_constant=invalid)
                if not isinstance(event,dict) or type(event.get('t')) not in (int,float):
                    raise ValueError('INVALID_RECORDING')
                if not 0 <= event['t'] <= 86400 or event['t'] < previous: raise ValueError('INVALID_TIMELINE')
                previous = event['t']
                if event.get('kind') in {'header','pose','state','lidar'}:
                    events.append(event)  # Action and RPC events remain evidence only.
        if (not events or events[0]['kind'] != 'header'
                or not isinstance(events[0].get('payload'), dict)
                or events[0]['payload'].get('schema') != 'taku-go2-recording-v1'):
            raise ValueError('INVALID_RECORDING_HEADER')
        return events


def replay_cloud(payload):
    values = dict(payload['values'])
    encoded = values.get('pointBytes')
    if not isinstance(encoded,dict) or encoded.get('encoding') != 'base64': raise ValueError('INVALID_POINT_ENCODING')
    raw = base64.b64decode(encoded['data'], validate=True)
    count = values.get('pointCount')
    if type(count) is not int or not 0 <= count <= 1000000 or len(raw) != count*12:
        raise ValueError('INVALID_POINT_LENGTH')
    import numpy as np
    if not np.isfinite(np.frombuffer(raw,dtype='<f4')).all(): raise ValueError('INVALID_POINTS')
    values['pointBytes'] = raw
    return {'values':values, 'received':time.monotonic(), 'count':payload.get('count',0)}

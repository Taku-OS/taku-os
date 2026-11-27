"""Full, finite point clouds and an aligned Float32 wire format; no downsampling."""
import json
import math
import struct
import numpy as np

MAX_INPUT_POINTS = 1000000  # Reject oversized frames rather than silently truncate.


def point_cloud(data):
    decoded = data.get('data', {})
    if not isinstance(decoded, dict):
        raise ValueError('Missing decoded point cloud')
    points = np.asarray(decoded.get('points', []), dtype=float)
    if points.size == 0:
        points = np.empty((0, 3))
    if points.ndim != 2 or points.shape[1] != 3 or len(points) > MAX_INPUT_POINTS:
        raise ValueError('Invalid point dimensions')
    input_count = len(points)
    points = points[np.isfinite(points).all(axis=1)]
    if len(points) and np.abs(points).max() > 10000:
        raise ValueError('Invalid coordinate range')
    count = len(points)
    if count:
        lower, upper = points.min(axis=0), points.max(axis=0)
        center = (lower + upper) / 2
        span = max(float((upper - lower).max()), 1.0)
        z_range = [float(lower[2]), float(upper[2])]
    else:
        center, span, z_range = np.zeros(3), 1.0, [0.0, 0.0]
    stamp = data.get('stamp')
    stamp_text = str(stamp)[:100] if isinstance(stamp, (str, int, float, dict)) else None
    resolution = data.get('resolution')
    return {'pointBytes': np.ascontiguousarray(points, dtype='<f4').tobytes(),
            'pointCount': count, 'displayCount': count, 'inputCount': input_count,
            'invalidCount': input_count - count, 'center': center.tolist(), 'span': span,
            'zRange': z_range, 'frameId': str(data.get('frame_id', ''))[:80],
            'resolution': resolution if type(resolution) in (float, int) and math.isfinite(resolution) else None,
            'sourceStamp': stamp_text}


def encode_frame(snapshot, points=b''):
    """u32 LE header length, UTF-8 JSON padded to 4 bytes, then XYZ float32 LE."""
    header = json.dumps({**snapshot, 'format': 'xyz-f32-le-v1', 'hasPoints': bool(points)},
                        allow_nan=False, separators=(',', ':')).encode('utf-8')
    header += b' ' * (-len(header) % 4)
    return struct.pack('<I', len(header)) + header + points

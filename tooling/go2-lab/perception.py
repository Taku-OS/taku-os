"""Explicit pose conventions and conservative local geometry for the simulator.

Real sport position and cloud odom are NOT assumed aligned. No calibration is
inferred from an API success. Invalid/missing frames yield unknown. A clear
simulator envelope only means no observed point intersects it, not proven free space.
"""
import math
import time


def vector(value, length):
    if not isinstance(value, (list, tuple)) or len(value) != length:
        return None
    if not all(type(v) in (int, float) and math.isfinite(v) for v in value):
        return None
    return [float(v) for v in value]


def sport_pose(data, frame=None, aligned=False):
    position = vector(data.get('position'), 3)
    imu = data.get('imu_state')
    quaternion = vector(imu.get('quaternion'), 4) if isinstance(imu, dict) else None
    if position is None or quaternion is None:
        return None
    norm = math.sqrt(sum(v*v for v in quaternion))
    if not .9 <= norm <= 1.1 or max(abs(v) for v in position) > 10000:
        return None
    q = [v/norm for v in quaternion]
    w, x, y, z = q  # Unitree sport IMU order; never infer order from magnitude.
    yaw = math.atan2(2*(w*z+x*y), 1-2*(y*y+z*z))
    stamp = data.get('stamp')
    if isinstance(stamp, dict):
        stamp = {k:v for k,v in stamp.items() if k in {'sec','nanosec'} and type(v) in (int,float) and math.isfinite(v)}
    elif type(stamp) not in (int,float) or not math.isfinite(stamp):
        stamp = None
    return {'position': position, 'quaternionWxyz': q, 'yaw': yaw, 'sourceStamp':stamp,
            'frameId': frame, 'aligned': aligned is True, 'source': 'sportmodestate'}


def world_to_body(points, pose):
    import numpy as np
    w, x, y, z = pose['quaternionWxyz']
    rotation = np.array([
        [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
        [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
        [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)],
    ])
    return (points - np.asarray(pose['position'])) @ rotation


def geometry(cloud, pose_sample, now=None):
    import numpy as np
    now = time.monotonic() if now is None else now
    unknown = {'valid': False, 'reason': 'NO_DATA', 'directions': {
        name: 'unknown' for name in ('forward', 'backward', 'left', 'right')},
        'points': [], 'footprint': [.45, .25], 'margin': .15}
    if not cloud or not pose_sample:
        return unknown
    pose, values = pose_sample['values'], cloud['values']
    for condition, reason in [
        (now-cloud['received'] > .4, 'CLOUD_STALE'),
        (now-pose_sample['received'] > .3, 'POSE_STALE'),
        (abs(cloud['received']-pose_sample['received']) > .2, 'TIME_SKEW'),
        (not pose.get('aligned'), 'POSE_UNVERIFIED'),
        (not pose.get('frameId') or pose['frameId'] != values.get('frameId'), 'FRAME_MISMATCH'),
    ]:
        if condition:
            return {**unknown, 'reason': reason}
    points = np.frombuffer(values.get('pointBytes', b''), dtype='<f4')
    if not points.size or points.size % 3 or not np.isfinite(points).all():
        return {**unknown, 'reason': 'EMPTY_OR_INVALID_CLOUD'}
    local = world_to_body(points.reshape(-1, 3), pose)
    # Simulator is a flat floor: body z=.32, ignore floor below body z=-.25.
    # This envelope is NOT calibrated for real steps, glass or drop-offs.
    obstacles = local[(local[:, 2] > -.25) & (local[:, 2] < .4)]
    obstacles = obstacles[np.linalg.norm(obstacles[:, :2], axis=1) < 3]
    directions = {}
    for name, dx, angle in [('forward', .028, 0), ('backward', -.028, 0),
                            ('left', 0, .0525), ('right', 0, -.0525)]:
        hit = False
        for fraction in np.linspace(0, 1, 8):
            c, s = math.cos(angle*fraction), math.sin(angle*fraction)
            x = obstacles[:, 0]-dx*fraction
            y = obstacles[:, 1]
            hit |= bool(np.any((np.abs(x*c+y*s) <= .60) & (np.abs(-x*s+y*c) <= .40)))
        directions[name] = 'blocked' if hit else 'clear'
    stride = max(1, math.ceil(len(obstacles)/1200))
    return {**unknown, 'valid': True, 'reason': None, 'directions': directions,
            'points': obstacles[::stride, :2].round(4).tolist(),
            'analyzedPoints': len(points)//3, 'obstaclePoints': len(obstacles),
            'scope': 'simulator-flat-floor-envelope', 'pose': pose,
            'cloudAgeMs': round((now-cloud['received'])*1000),
            'poseAgeMs': round((now-pose_sample['received'])*1000)}

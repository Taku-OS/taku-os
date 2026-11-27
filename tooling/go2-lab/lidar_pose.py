"""Validated PoseStamped input from the robot's own LiDAR odometry topic.

Frame agreement is reported separately from physical calibration. Receiving an
`odom` label alone does not authorize movement or establish a camera transform.
"""
import math
import time
from perception import vector

TOPIC = 'rt/utlidar/robot_pose'


def decode(data):
    try:
        header, pose = data['header'], data['pose']
        if header['frame_id'] != 'odom': raise ValueError('FRAME_UNVERIFIED')
        position = vector([pose['position'][k] for k in ('x', 'y', 'z')], 3)
        q = vector([pose['orientation'][k] for k in ('w', 'x', 'y', 'z')], 4)
        stamp = header['stamp']; sec, ns = stamp['sec'], stamp['nanosec']
        if type(sec) is not int or type(ns) is not int or sec < 0 or not 0 <= ns < 10**9:
            raise ValueError('INVALID_POSE_STAMP')
        if position is None or q is None or max(map(abs, position)) > 10000:
            raise ValueError('INVALID_POSE')
        norm = math.sqrt(sum(v*v for v in q))
        if not .99 <= norm <= 1.01: raise ValueError('INVALID_QUATERNION')
        q = [v/norm for v in q]; w, x, y, z = q
        return {'position': position, 'quaternionWxyz': q,
                'yaw': math.atan2(2*(w*z+x*y), 1-2*(y*y+z*z)),
                'sourceStamp': {'sec': sec, 'nanosec': ns}, 'frameId': 'odom',
                'aligned': True, 'source': TOPIC}
    except (TypeError, KeyError, AttributeError):
        raise ValueError('INVALID_POSE') from None


class LidarPose:
    def __init__(self):
        self.sample = None; self.error = None; self.last_stamp = None; self.count = 0

    def receive(self, data, now=None):
        now = time.monotonic() if now is None else now
        try:
            value = decode(data)
            stamp = value['sourceStamp']; stamp = stamp['sec']*10**9+stamp['nanosec']
            if self.last_stamp is not None and stamp <= self.last_stamp:
                raise ValueError('POSE_CLOCK_RESET_OR_REPLAY')
            if self.sample:
                old = self.sample['values']; dt = now-self.sample['received']
                yaw = abs((value['yaw']-old['yaw']+math.pi)%(2*math.pi)-math.pi)
                if dt <= 0 or math.dist(value['position'],old['position']) > .08+1.0*dt or yaw > .12+1.0*dt:
                    raise ValueError('POSE_JUMP')
            # A discontinuity latches until a new connection, never self-clears.
            if self.error: return False
            self.sample = {'values':value,'received':now}; self.last_stamp=stamp; self.count+=1
            return True
        except ValueError as e:
            self.error=str(e); return False

    def snapshot(self):
        return {'source':TOPIC,'count':self.count,'error':self.error,
                'ageMs':round((time.monotonic()-self.sample['received'])*1000) if self.sample else None,
                'frameId':self.sample['values']['frameId'] if self.sample else None,
                'physicalCalibrationVerified':False}

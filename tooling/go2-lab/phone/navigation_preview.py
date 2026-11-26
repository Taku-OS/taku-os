"""Map/odometry/LiDAR/person fusion in observation mode. No actuator dependency.

Produces bounded body-frame velocity proposals, never commands. All calibration
uncertainties are declared estimates, not a measured localization covariance.
"""
import math
import re
import struct
import time
from geo_reference import GeoReference, RouteProgress, finite, coordinate, wrap


class NavigationPreview:
    def __init__(self, bridge):
        self.bridge = bridge
        self.reference = self.route = self.route_info = self.phone = self.vision = None
        self.floor_z = None
        self.anchor_source = None
        self.last_phone_sequence = -1
        self.phone_session = None
        self.phone_providers = {}
        self.connection_reference = None

    def pose(self):
        b = self.bridge
        if b.phase != 'connected' or not b.pose_sample or not 0 <= time.monotonic() - b.pose_sample['received'] < .3:
            raise ValueError('POSE_STALE')
        p = b.pose_sample['values']
        if not p.get('aligned') or not p.get('frameId') or not all(finite(v) for v in [*p['position'], p['yaw']]):
            raise ValueError('POSE_FRAME_UNVERIFIED')
        return p

    def phone_fix(self, value):
        fields = {'session', 'sequence', 'longitude', 'latitude', 'accuracy', 'ageMs', 'provider', 'mock'}
        if (not isinstance(value, dict) or set(value) != fields
                or not isinstance(value['session'], str) or not re.fullmatch(r'[a-f0-9-]{36}', value['session'])
                or type(value['sequence']) is not int or value['sequence'] < 0
                or value['provider'] not in {'gps', 'network', 'fused'} or type(value['mock']) is not bool
                or not all(finite(value[k]) for k in ('longitude', 'latitude', 'accuracy', 'ageMs'))
                or not 0 < value['accuracy'] <= 10000 or not 0 <= value['ageMs'] < 30000):
            raise ValueError('INVALID_PHONE_FIX')
        coordinate([value['longitude'], value['latitude']])
        if value['session'] == self.phone_session and value['sequence'] <= self.last_phone_sequence:
            raise ValueError('STALE_PHONE_FIX')
        if value['session'] != self.phone_session: self.phone_providers.clear()
        self.phone_session, self.last_phone_sequence = value['session'], value['sequence']
        self.phone = {**value, 'received': time.monotonic() - value['ageMs'] / 1000}
        self.phone_providers[value['provider']] = self.phone
        return self.phone_view()

    def phone_view(self):
        if self.phone is None: return {'valid': False, 'reason': 'PHONE_FIX_REQUIRED', 'coordinateSystem': 'WGS84'}
        fresh = [p for p in self.phone_providers.values() if not p['mock'] and time.monotonic()-p['received'] < 10]
        p = min(fresh, key=lambda p: p['accuracy']) if fresh else self.phone
        age = max(0, time.monotonic() - p['received'])
        return {'valid': age < 10 and not p['mock'], 'reason': 'PHONE_MOCK_FIX' if p['mock'] else 'PHONE_FIX_STALE' if age >= 10 else None,
                'position': [p['longitude'], p['latitude']], 'accuracyMeters': p['accuracy'], 'ageSeconds': round(age, 1),
                'provider': p['provider'], 'coordinateSystem': 'WGS84', 'isRobotPosition': False,
                'accuracyMeaning': 'android-68-percent-radius',
                'providers': {name: {'ageSeconds': round(max(0,time.monotonic()-v['received']),1), 'accuracyMeters': v['accuracy'], 'mock': v['mock']}
                              for name, v in self.phone_providers.items()}}

    def calibrate(self, value):
        fields = {'longitude', 'latitude', 'heading', 'accuracy', 'headingAccuracy', 'floorZ', 'source', 'colocated'}
        if (not isinstance(value, dict) or set(value) != fields
                or not all(finite(value[k]) for k in fields - {'source', 'colocated'})
                or value['source'] not in {'surveyed', 'phone-colocated'} or type(value['colocated']) is not bool
                or not .05 <= value['accuracy'] <= 100 or not .5 <= value['headingAccuracy'] <= 90
                or not -100 <= value['floorZ'] <= 100): raise ValueError('INVALID_ANCHOR')
        if value['source'] == 'phone-colocated':
            p = self.phone_view()
            if not value['colocated'] or not p['valid'] or p['accuracyMeters'] > 5 or value['accuracy'] < p['accuracyMeters']:
                raise ValueError('PHONE_COLOCATION_REQUIRED')
        p = self.pose()
        self.reference = GeoReference([value['longitude'], value['latitude']], p['position'][:2], p['yaw'], value['heading'],
                                      self.bridge.stream_id, p['frameId'], 'manual', value['accuracy'], value['headingAccuracy'])
        self.floor_z, self.anchor_source = value['floorZ'], value['source']
        self.connection_reference = getattr(self.bridge, 'lidar_pose', None)
        self.route = self.route_info = None
        return self.view()

    def robot(self):
        if self.reference is None: return {'valid': False, 'reason': 'ROBOT_ANCHOR_REQUIRED'}
        if self.bridge.phase != 'connected': self.reference.invalid = 'CONNECTION_CHANGED'
        if getattr(self.bridge, 'lidar_pose', None) is not self.connection_reference:
            self.reference.invalid = 'CONNECTION_CHANGED'
        result = self.reference.update(self.bridge.pose_sample, self.bridge.stream_id, 'manual')
        result.update(anchorSource=self.anchor_source, uncertaintyKind='assumed-growth-not-measured', absoluteFix=False)
        return result

    def prepare(self, value):
        robot = self.robot()
        if not robot.get('valid'): raise ValueError(robot.get('reason') or 'ROBOT_ANCHOR_REQUIRED')
        if robot['assumedErrorMeters'] > 5: raise ValueError('POSITION_TOO_UNCERTAIN')
        if (not isinstance(value, dict) or value.get('coordinateSystem') != 'GCJ-02'
                or value.get('protocol') != 'mcp' or value.get('routeAgreement') is not True):
            raise ValueError('MAP_ROUTE_DISAGREEMENT')
        points = []
        for step in value.get('steps', []):
            for point in step.get('polyline', []):
                points.append(coordinate([float(v) for v in point.split(',')]))
                if len(points) > 5000: raise ValueError('ROUTE_LIMIT')
        if len(points) < 2: raise ValueError('NO_ROUTE_GEOMETRY')
        route = RouteProgress([self.reference.to_local(p) for p in points])
        pose = self.pose()
        if math.dist(route.points[0], pose['position'][:2]) > 2: raise ValueError('ROUTE_START_TOO_FAR')
        self.route = route
        self.route_info = {'geometryId': value.get('geometryId'), 'distance': route.lengths[-1],
                           'geometrySource': value.get('geometrySource'), 'protocol': 'mcp'}
        return self.view()

    def observe(self, value):
        if (not isinstance(value, dict) or set(value) != {'frameKey', 'boxes'} or not isinstance(value['frameKey'], str)
                or not isinstance(value['boxes'], list) or len(value['boxes']) > 3): raise ValueError('INVALID_VISION')
        for box in value['boxes']:
            if not isinstance(box, list) or len(box) != 4 or not all(finite(n) and 0 <= n <= 1 for n in box):
                raise ValueError('INVALID_VISION')
            if box[2] <= 0 or box[3] <= 0 or box[0]+box[2] > 1.001 or box[1]+box[3] > 1.001:
                raise ValueError('INVALID_VISION')
        timestamp = self.bridge.camera.frame_time(value['frameKey'])
        if timestamp is None or not 0 <= time.monotonic() - timestamp < 1:
            raise ValueError('VISION_STALE')
        if self.vision and timestamp <= self.vision['received']: raise ValueError('VISION_STALE')
        self.vision = {**value, 'received': timestamp}
        return {'accepted': True}

    def corridor(self, pose):
        sample = self.bridge.lidar_sample
        now = time.monotonic()
        if not self.bridge.lidar_enabled or not sample or not 0 <= now - sample['received'] < .4:
            return 'LIDAR_STALE', None
        if abs(sample['received'] - self.bridge.pose_sample['received']) > .2: return 'SENSOR_SKEW', None
        if sample['values'].get('frameId') != pose['frameId']: return 'LIDAR_FRAME_MISMATCH', None
        raw = sample['values'].get('pointBytes', b'')
        if not raw or len(raw) % 12 or len(raw) > 3_000_000: return 'LIDAR_INVALID', None
        c, s = math.cos(pose['yaw']), math.sin(pose['yaw'])
        ground, nearest = set(), None
        for x, y, z in struct.iter_unpack('<fff', raw):
            if not all(math.isfinite(v) for v in (x, y, z)): continue
            dx, dy = x-pose['position'][0], y-pose['position'][1]
            forward, side = dx*c+dy*s, -dx*s+dy*c
            if abs(side) > .55 or not .25 < forward < 2: continue
            height = z - self.floor_z
            if abs(height) <= .07 and .4 <= forward < 1.4:
                ground.add((int((forward-.4)/.2), min(2, max(0, int((side+.45)/.3)))))
            if .08 < height < 1.3: nearest = forward if nearest is None else min(nearest, forward)
        if nearest is not None and nearest < 1: return 'LIDAR_OBSTACLE', round(nearest, 2)
        # Unknown space / stairs / absent floor are never treated as traversable.
        if len(ground) < 15: return 'GROUND_UNOBSERVED', nearest
        return None, nearest

    def view(self):
        now = time.monotonic()
        robot = self.robot()
        result = {'mode': 'observation', 'realActuationAllowed': False, 'phone': self.phone_view(), 'robot': robot,
                  'route': self.route_info, 'proposal': {'forwardMps': 0., 'yawRadps': 0.}, 'reasons': [],
                  'limits': {'forwardMps': .2, 'yawRadps': .25}, 'fieldValidationRequired': True,
                  'cameraScope': 'person-veto-only', 'groundModel': 'declared-flat-floor'}
        camera = self.bridge.camera.snapshot()
        cloud = getattr(self.bridge, 'lidar_sample', None)
        pose_sample = getattr(self.bridge, 'pose_sample', None)
        result['sensors'] = {'cameraFresh': bool(camera.get('fresh')),
                             'visionFresh': bool(self.vision and 0 <= now-self.vision['received'] < 1),
                             'lidarFresh': bool(cloud and self.bridge.lidar_enabled and 0 <= now-cloud['received'] < .4),
                             'odometryFresh': bool(pose_sample and 0 <= now-pose_sample['received'] < .3),
                             'people': len(self.vision['boxes']) if self.vision and 0 <= now-self.vision['received'] < 1 else None}
        reasons = result['reasons']
        if self.bridge.phase != 'connected': reasons.append('NOT_CONNECTED')
        if not robot.get('valid'): reasons.append(robot.get('reason') or 'ROBOT_ANCHOR_REQUIRED')
        elif robot['assumedErrorMeters'] > 1: reasons.append('POSITION_TOO_UNCERTAIN')
        if self.route is None: reasons.append('MAP_ROUTE_REQUIRED')
        if reasons: return result
        try: pose = self.pose()
        except ValueError as e: reasons.append(str(e)); return result
        reason, distance = self.corridor(pose)
        result['obstacleDistanceMeters'] = distance
        if reason: reasons.append(reason)
        camera = self.bridge.camera.snapshot()
        if not camera.get('fresh') or not self.vision or not 0 <= now-self.vision['received'] < 1:
            reasons.append('VISION_STALE')
        elif any(x < .7 and x+w > .3 for x, y, w, h in self.vision['boxes']): reasons.append('PERSON_IN_PATH')
        try:
            self.route.project(pose['position'][:2])
            remaining = self.route.lengths[-1] - self.route.progress
            result['progressMeters'], result['remainingMeters'] = self.route.progress, remaining
            if remaining < .4 and math.dist(pose['position'][:2], self.route.points[-1]) < .4:
                reasons.append('ARRIVED'); return result
            target = self.route.at(self.route.progress + .8)
            error = wrap(math.atan2(target[1]-pose['position'][1], target[0]-pose['position'][0])-pose['yaw'])
            result['headingErrorDegrees'] = round(math.degrees(error), 1)
            if not reasons:
                result['proposal'] = {'forwardMps': round(min(.2, remaining*.3)*max(0, 1-abs(error)/.6), 3),
                                      'yawRadps': round(max(-.25, min(.25, error*.6)), 3)}
        except ValueError as e: reasons.append(str(e))
        return result

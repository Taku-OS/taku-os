"""Explicit GCJ-02 map-to-odometry reference, not GNSS or a SLAM estimator."""
import math
import time

EARTH_RADIUS = 6378137.0


def finite(value): return type(value) in (int, float) and math.isfinite(value)


def coordinate(value):
    if not isinstance(value, (list, tuple)) or len(value) != 2 or not all(finite(v) for v in value):
        raise ValueError('INVALID_GEO_COORDINATE')
    if not -180 <= value[0] <= 180 or not -75 <= value[1] <= 75:
        raise ValueError('INVALID_GEO_COORDINATE')
    return list(value)


def wrap(value): return (value + math.pi) % (2 * math.pi) - math.pi


class GeoReference:
    def __init__(self, anchor, position, yaw, heading, epoch, frame, source, accuracy=3., heading_accuracy=20.):
        self.anchor = coordinate(anchor)
        if not all(finite(v) for v in [*position, yaw, heading, accuracy, heading_accuracy]) or len(position) != 2:
            raise ValueError('INVALID_CALIBRATION')
        if not 0 <= heading < 360 or not 0 <= accuracy <= 100 or not 0 <= heading_accuracy <= 180:
            raise ValueError('INVALID_CALIBRATION')
        self.position = list(position)
        # Compass heading is clockwise from north; odom yaw is CCW from its own x.
        self.rotation = wrap(math.pi / 2 - math.radians(heading) - yaw)
        self.epoch, self.frame, self.source = epoch, frame, source
        self.accuracy, self.heading_accuracy = accuracy, heading_accuracy
        self.created = time.monotonic()
        self.last = None
        self.distance = 0.
        self.invalid = None

    def meters(self, geo):
        lon, lat = coordinate(geo)
        east = EARTH_RADIUS * math.radians(lon - self.anchor[0]) * math.cos(math.radians(self.anchor[1]))
        north = EARTH_RADIUS * math.radians(lat - self.anchor[1])
        if math.hypot(east, north) > 20000: raise ValueError('GEO_EXTENT_EXCEEDED')
        return [east, north]

    def to_local(self, geo):
        east, north = self.meters(geo)
        c, s = math.cos(self.rotation), math.sin(self.rotation)
        return [self.position[0] + east*c + north*s, self.position[1] - east*s + north*c]

    def to_geo(self, local):
        dx, dy = local[0] - self.position[0], local[1] - self.position[1]
        c, s = math.cos(self.rotation), math.sin(self.rotation)
        return [self.anchor[0] + math.degrees((dx*c-dy*s) / (EARTH_RADIUS*math.cos(math.radians(self.anchor[1])))),
                self.anchor[1] + math.degrees((dx*s+dy*c) / EARTH_RADIUS)]

    def update(self, sample, epoch, source, now=None):
        now = time.monotonic() if now is None else now
        if epoch != self.epoch or source != self.source:
            self.invalid = 'GEO_REFERENCE_CHANGED'
        if not sample or now - sample['received'] > .3:
            return {'valid': False, 'reason': self.invalid or 'POSE_STALE'}
        pose = sample['values']
        if pose.get('frameId') != self.frame:
            self.invalid = 'GEO_FRAME_CHANGED'
        if self.last and sample['received'] > self.last[0]:
            dt = sample['received'] - self.last[0]
            distance = math.dist(pose['position'][:2], self.last[1])
            turn = abs(wrap(pose['yaw'] - self.last[2]))
            if dt > 1. or distance > 2*dt+.2 or turn > 2*dt+.3:
                self.invalid = 'ODOMETRY_DISCONTINUITY'
            self.distance += distance
        self.last = (sample['received'], list(pose['position'][:2]), pose['yaw'])
        elapsed = max(0., now - self.created)
        # Conservative *assumed* uncertainty, not a measured covariance/confidence interval.
        uncertainty = self.accuracy if self.source == 'simulation' else self.accuracy + self.distance*(.05+math.sin(math.radians(min(self.heading_accuracy,90)))) + elapsed*.002
        return {'valid': self.invalid is None, 'reason': self.invalid,
                'coordinateSystem': 'GCJ-02', 'position': self.to_geo(pose['position']),
                'heading': round(90-math.degrees(pose['yaw']+self.rotation), 9) % 360,
                'assumedErrorMeters': uncertainty, 'distanceSinceCalibration': self.distance,
                'ageSeconds': elapsed, 'source': self.source, 'absoluteFix': False,
                'frameId': self.frame, 'epoch': self.epoch}


def polyline(points):
    if not isinstance(points, list) or not 2 <= len(points) <= 5000: raise ValueError('INVALID_GEO_ROUTE')
    return [coordinate(p) for p in points]


class RouteProgress:
    """Monotonic local projection; cannot jump to a distant crossing of a route."""
    def __init__(self, points):
        self.points = []
        for p in points:
            if not self.points or math.dist(p, self.points[-1]) > .02: self.points.append(list(p))
        if len(self.points) < 2: raise ValueError('ROUTE_TOO_SHORT')
        self.lengths = [0.]
        for a, b in zip(self.points, self.points[1:]): self.lengths.append(self.lengths[-1] + math.dist(a, b))
        if not .5 <= self.lengths[-1] <= 20000: raise ValueError('ROUTE_LENGTH_UNSUPPORTED')
        self.progress = 0.

    def at(self, distance):
        distance = max(0., min(distance, self.lengths[-1]))
        for i, end in enumerate(self.lengths[1:]):
            if end >= distance:
                t = (distance-self.lengths[i])/(end-self.lengths[i])
                return [a+(b-a)*t for a,b in zip(self.points[i],self.points[i+1])]
        return self.points[-1]

    def project(self, position):
        candidates = []
        for i, (a,b) in enumerate(zip(self.points,self.points[1:])):
            low, high = self.lengths[i], self.lengths[i+1]
            if low > self.progress+2 or high < self.progress-.5: continue
            d = high-low
            t = max(0., min(1., sum((p-x)*(y-x) for p,x,y in zip(position,a,b))/(d*d)))
            s = low+t*d
            if s > self.progress+2 or s < self.progress-.5: continue
            q = [x+(y-x)*t for x,y in zip(a,b)]
            candidates.append((math.dist(position,q),s))
        if not candidates: raise ValueError('ROUTE_DEVIATION')
        distance, progress = min(candidates)
        if distance > 1.6: raise ValueError('ROUTE_DEVIATION')
        self.progress = max(self.progress,progress)
        return distance

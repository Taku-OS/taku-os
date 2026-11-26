"""Geographic mission data and bounded local targets; no robot transport here."""
import asyncio
import hashlib
import math
import time
import uuid
from geo_reference import GeoReference, RouteProgress, polyline, finite


def valid_geo(action, value):
    if not isinstance(value, dict): return False
    if action == 'geo-prepare':
        if set(value) != {'points','name','scope'} or value['scope'] not in {'full','first10m'}: return False
        if not isinstance(value['name'], str) or not 0 < len(value['name'].strip()) <= 80: return False
        try: polyline(value['points']); return True
        except ValueError: return False
    if action == 'geo-calibrate':
        return (set(value) == {'mapId','longitude','latitude','heading'} and isinstance(value['mapId'], str) and len(value['mapId'])==32
                and all(finite(value[k]) for k in ('longitude','latitude','heading'))
                and -180 <= value['longitude'] <= 180 and -75 <= value['latitude'] <= 75 and 0 <= value['heading'] < 360)
    if action in {'geo-start','geo-heartbeat'}:
        return set(value) == {'mapId','routeId'} and all(isinstance(v,str) and len(v)==32 for v in value.values())
    return False


class GeoMission:
    def __init__(self, navigation):
        self.nav = navigation
        self.reference = None
        self.route = None
        self.route_id = None
        self.points = []
        self.name = ''
        self.scope = None
        self.full_length = 0.
        self.executing = False
        self.heartbeat = 0.
        self.cross_track = 0.
        self.geometry_id = None

    def invalidate(self, reason):
        if self.reference: self.reference.invalid = reason

    def localization(self):
        b = self.nav.bridge
        if not self.reference: return {'valid':False, 'reason':'GEO_CALIBRATION_REQUIRED'}
        if b.phase != 'connected': self.invalidate('GEO_REFERENCE_CHANGED')
        return self.reference.update(b.pose_sample,self.nav.map_id,'simulation' if b.simulated else 'manual')

    def snapshot(self):
        return {'localization':self.localization(), 'routeId':self.route_id, 'geometryId':self.geometry_id, 'name':self.name, 'scope':self.scope,
                'executionMode':'simulation-only', 'realActuationAllowed':False,
                'active':bool(self.executing and self.nav.active()), 'fullLength':self.full_length,
                'length':self.route.lengths[-1] if self.route else 0.,
                'progress':self.route.progress if self.route else 0., 'crossTrack':self.cross_track,
                'route':self.route.points if self.route else [],
                'blockers':[] if self.nav.bridge.simulated else ['ABSOLUTE_LOCATION_UNVERIFIED', 'LIDAR_ODOM_ALIGNMENT_UNVERIFIED', 'REAL_AVOIDANCE_CONTROL_UNVERIFIED']}

    def calibrate(self, value):
        n,b=self.nav,self.nav.bridge
        if value['mapId'] != n.map_id: raise ValueError('MAP_CHANGED')
        if b.phase != 'connected' or not b.pose_sample or time.monotonic()-b.pose_sample['received']>.3: raise ValueError('POSE_STALE')
        if n.active() or b.lease.owner: raise ValueError('CONTROL_BUSY')
        pose=b.pose_sample['values']
        self.reference=GeoReference([value['longitude'],value['latitude']],pose['position'][:2],pose['yaw'],value['heading'],
                                    n.map_id,pose.get('frameId'),'simulation' if b.simulated else 'manual')
        # Recalibrating invalidates the previously transformed route.
        self.route=None;self.route_id=None;self.geometry_id=None;self.executing=False

    async def prepare(self, value):
        n,b=self.nav,self.nav.bridge
        if not b.simulated: raise ValueError('REAL_NAVIGATION_DISABLED')
        if n.active() or b.lease.owner or b.motion.snapshot()['armed'] or b.avoidance.snapshot()['armed']: raise ValueError('CONTROL_BUSY')
        points=polyline(value['points'])
        origin=points[0]
        frame=GeoReference(origin,[0,0],0,90,'preview','odom','simulation',.02,.5)
        local=[frame.to_local(p) for p in points]
        route=RouteProgress(local)
        direction=next(p for p in route.points[1:] if math.dist(p,[0,0])>.02)
        heading=(90-math.degrees(math.atan2(direction[1],direction[0])))%360
        frame=GeoReference(origin,[0,0],0,heading,'preview','odom','simulation',.02,.5)
        route=RouteProgress([frame.to_local(p) for p in points])
        full_length=route.lengths[-1]
        if value['scope']=='first10m' and full_length>10:
            route=RouteProgress([p for p,s in zip(route.points,route.lengths) if s<10]+[route.at(10)])
        generation=n.generation
        if b.phase!='connected':
            await b.command('connect')
            for _ in range(30):
                if b.phase=='connected': break
                await asyncio.sleep(.05)
        if generation!=n.generation: raise ValueError('CANCELLED')
        # Scenario reset starts a new odometry/map epoch. It cannot load a real transport.
        await b.camera.command('stop')
        await b.set_scenario('geo-route')
        if n.generation!=generation+2: raise ValueError('CANCELLED')
        generation=n.generation
        mission=n.geo
        mission.reference=GeoReference(origin,[0,0],0,heading,n.map_id,'odom','simulation',.02,.5)
        mission.route=route;mission.points=points;mission.full_length=full_length
        mission.geometry_id=hashlib.sha256(';'.join(f'{lon:.6f},{lat:.6f}' for lon,lat in points).encode()).hexdigest()
        mission.name=value['name'];mission.scope=value['scope'];mission.route_id=uuid.uuid4().hex
        b.geo_obstacle=route.at(min(1.3,route.lengths[-1]/2)) if route.lengths[-1]>3 else None
        b.update_sensors(force=True)
        await b.camera.command('start')
        for _ in range(30):
            if not n.guard():break
            await asyncio.sleep(.05)
        if generation!=n.generation: raise ValueError('CANCELLED')
        if n.guard(): raise ValueError(n.guard())
        await n.rebuild()
        if generation!=n.generation: raise ValueError('CANCELLED')

    def verify(self, value):
        if value['mapId']!=self.nav.map_id or value['routeId']!=self.route_id or not self.route: raise ValueError('GEO_ROUTE_CHANGED')

    def guard(self):
        pose=self.localization()
        if not pose['valid']: return pose['reason']
        if pose['assumedErrorMeters']>2.: return 'GEO_UNCERTAINTY_HIGH'
        if time.monotonic()-self.heartbeat>3.: return 'SUPERVISION_LOST'
        return None

    def target(self, position, grid):
        self.cross_track=self.route.project(position)
        # Never skip to a distant crossing; stop if the current observed corridor has no route.
        for ahead in [2.2,2.,1.8,1.5,1.2,.8]:
            target=self.route.at(self.route.progress+ahead)
            try: return target,grid.route(position,target)
            except ValueError: pass
        raise ValueError('GEO_LOCAL_PATH_BLOCKED')

    def arrived(self, position):
        self.route.project(position)
        return self.route.lengths[-1]-self.route.progress<.18 and math.dist(position,self.route.points[-1])<.14

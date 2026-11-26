#!/usr/bin/env python3
"""Isolated Go2 development simulator. No real driver or robot IP accepted.

Kinematic flat-floor model only, not Unitree firmware or a physics simulator.
Launch on the same loopback port as the APK; cannot switch into real mode.
"""
import asyncio
import math
import signal
import sys
import threading
import time
from types import SimpleNamespace
from http.server import ThreadingHTTPServer
from bridge import Bridge, handler_class, PORT
from lab import SCENARIOS
from avoidance import Avoidance
from perception import sport_pose


class SimNative:
    actuation_allowed = True
    def __init__(self, bridge): self.bridge=bridge; self.enabled=False; self.claimed=False
    async def query(self):
        if self.bridge.phase != 'connected': raise ConnectionError('DISCONNECTED')
        if self.bridge.scenario == 'query-timeout': await asyncio.Event().wait()
        if self.bridge.scenario == 'invalid-state': return 'true'
        return self.enabled
    async def set(self, value):
        if self.bridge.scenario == 'enable-rejected': raise RuntimeError('SIM_REJECTED')
        self.enabled = False if self.bridge.scenario == 'readback-mismatch' else value
        self.bridge.recorder.emit('native_rpc',{'api':1001,'enable':value,'simulated':True})
    async def claim(self):
        if not self.enabled: raise RuntimeError('AVOIDANCE_DISABLED')
        self.claimed=True
    def move(self, vector):
        if not self.claimed or not self.enabled: raise RuntimeError('NOT_CLAIMED')
        self.bridge.desired=vector
        self.bridge.last_command=time.monotonic()
        if self.bridge.move_started is None: self.bridge.move_started=time.monotonic()
        self.bridge.sim_commands+=1
        self.bridge.recorder.emit('native_move',{'vector':vector,'simulated':True})
    async def stop(self):
        if self.bridge.scenario == 'stop-timeout': await asyncio.Event().wait()
        self.bridge.desired=(0,0,0);self.claimed=False
        self.bridge.recorder.emit('native_stop',{'simulated':True})
    def allows(self, _): return False


class SimBridge(Bridge):
    def __init__(self):
        super().__init__('SIMULATION')
        self.simulated=True
        self.scenario='clear'
        self.position=[0.,0.,.32];self.yaw=0.
        self.desired=(0,0,0);self.last_command=0;self.move_started=None
        self.native_blocked=False;self.sim_commands=0
        self.actual_velocity=(0,0,0);self.native_blocks=0
        self.native=SimNative(self)
        self.avoidance=Avoidance(self.native,self.protected_ready,self.lease,self.recorder.emit)
        self.geo_obstacle=None
        self.world_points=self.make_cloud()

    def make_cloud(self):
        import numpy as np
        if self.scenario=='geo-route':
            cx,cy=[round(v/.1)*.1 for v in self.position[:2]]
            floor=np.array([[cx+x,cy+y,0] for x in np.linspace(-3,3,61) for y in np.linspace(-3,3,61)])
            if self.geo_obstacle is None:return floor
            x,y=self.geo_obstacle
            box=np.array([[x+dx,y+dy,z] for dx in (-.12,0,.12) for dy in (-.12,0,.12) for z in (.1,.2,.3)])
            return np.concatenate([floor,box])
        floor=np.array([[x,y,0] for x in np.linspace(-2.5,2.5,64) for y in np.linspace(-2.5,2.5,64)])
        wall=np.array([[x,y,z] for x in (-2.5,2.5) for y in np.linspace(-2.5,2.5,70) for z in np.linspace(0,.8,9)]
                      +[[x,y,z] for y in (-2.5,2.5) for x in np.linspace(-2.5,2.5,70) for z in np.linspace(0,.8,9)])
        if self.scenario.startswith('navigation-'):
            floor=np.array([[x,y,0] for x in np.linspace(-3,3,61) for y in np.linspace(-3,3,61)])
            wall=np.array([[x,y,z] for x in (-2.8,2.8) for y in np.linspace(-3,3,100) for z in (.1,.4,.7)]
                          +[[x,y,z] for y in (-2.8,2.8) for x in np.linspace(-3,3,100) for z in (.1,.4,.7)])
            box=np.array([[x,y,z] for x in (.9,1.) for y in (-.1,0,.1) for z in (.1,.2,.3)])
            if self.scenario=='navigation-blocked':
                box=np.array([[.95,y,z] for y in np.linspace(-3,3,61) for z in (.1,.3)])
            table=np.array([[x,y,.65] for x in np.linspace(1.45,1.95,6) for y in np.linspace(-1.75,-1.2,7)])
            door=np.array([[2.5,y,z] for y in np.linspace(.94,1.66,10) for z in (.1,.4,.7)])
            return np.concatenate([floor,wall,box,table,door])
        positions={'front':(.61,0),'rear':(-.61,0),'left':(.58,.42),'right':(.58,-.42)}
        name=self.scenario
        if name=='obstacle-appears' and self.move_started is not None and time.monotonic()-self.move_started>.10:
            name='front'
        if name in positions:
            x,y=positions[name]
            obstacle=np.array([[x+dx,y+dy,z] for dx in (0,.01) for dy in (-.004,0,.004) for z in (.1,.2,.3,.4)])
            return np.concatenate([floor,wall,obstacle])
        return np.concatenate([floor,wall])

    def simulation_status(self):
        return {'position':list(self.position), 'yaw':self.yaw, 'commands':self.sim_commands,
                'nativeBlocked':self.native_blocked, 'model':'kinematic-flat-floor',
                'nativeBlocks':self.native_blocks,
                'realTransportLoaded':False}

    async def set_scenario(self, name):
        if name not in SCENARIOS and name!='geo-route': return {'error':'UNKNOWN_SCENARIO'}
        await self.navigation.stop()
        if not self.navigation.stop_confirmed: return {'error':'STOP_UNCONFIRMED'}
        self.navigation.reset()
        if self.motion.snapshot()['armed'] or self.motion.busy: await self.motion.stop()
        if self.avoidance.snapshot()['armed'] or self.avoidance.busy: await self.avoidance.stop()
        self.avoidance.revoke();self.avoidance.enabled=None;self.avoidance.checked=0
        self.scenario=name;self.position=[0.,0.,.32];self.yaw=0.;self.desired=(0,0,0)
        self.move_started=None;self.native_blocked=False
        self.actual_velocity=(0,0,0);self.native_blocks=0
        self.native.enabled=False;self.native.claimed=False
        self.avoidance.cooldown=0
        self.current_motion_mode='ai' if name=='mode-change' else 'normal'
        self.world_points=self.make_cloud()
        if name=='disconnect':
            await self.command('disconnect')
        else:
            self.update_sensors(force=True)
        self.recorder.emit('scenario',{'name':name,'simulated':True})
        return await self.lab_snapshot()

    def update_sensors(self, force=False):
        stamp={'sec':int(time.time()),'nanosec':int(time.time()%1*1e9)}
        data={'body_height':.32,'mode':1,'velocity':[self.actual_velocity[0],self.actual_velocity[1],0],
              'yaw_speed':self.actual_velocity[2], 'position':list(self.position),
              'imu_state':{'quaternion':[math.cos(self.yaw/2),0,0,math.sin(self.yaw/2)]},'stamp':stamp}
        # Keep regular state fresh while testing independent pose staleness.
        old_pose=self.pose_sample
        if not force and self.scenario=='stale-pose':
            data.pop('position');data.pop('imu_state')
        self.receive({'topic':'rt/lf/sportmodestate','data':data})
        if force or self.scenario!='stale-pose':
            pose=sport_pose(data,'different' if self.scenario=='pose-mismatch' else 'odom',True)
            self.pose_sample={'values':pose,'received':time.monotonic()}
            self.recorder.emit('pose',pose)
        else:self.pose_sample=old_pose
        self.receive({'topic':'rt/lf/lowstate','data':{'bms_state':{'soc':80},'power_v':30.0}})
        if self.lidar_enabled and (force or self.scenario!='stale-cloud'):
            if self.scenario in {'obstacle-appears','geo-route'}:self.world_points=self.make_cloud()
            points=[] if self.scenario=='empty-cloud' else self.world_points
            self.receive_lidar({'data':{'frame_id':'odom','stamp':stamp,'resolution':.05,'data':{'points':points}}})

    async def session(self):
        self.conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True))
        self.phase='connected';self.current_motion_mode='normal';self.lidar_enabled=True
        self.update_sensors(force=True)
        previous=time.monotonic();next_frame=0
        try:
            while True:
                now=time.monotonic();dt=min(now-previous,.05);previous=now
                self.native_blocked=self.scenario=='native-block' and any(self.desired)
                if now-self.last_command>.15: self.desired=(0,0,0)
                vx,vy,wz=(0,0,0) if self.native_blocked else self.desired
                if (self.scenario.startswith('navigation-') or self.scenario=='geo-route') and (vx or vy):
                    # Independent physical envelope. Planner errors cannot pass through a box.
                    nx=self.position[0]+(vx*math.cos(self.yaw)-vy*math.sin(self.yaw))*dt
                    ny=self.position[1]+(vx*math.sin(self.yaw)+vy*math.cos(self.yaw))*dt
                    obstacles=self.world_points[self.world_points[:,2]>.06]
                    if ((obstacles[:,0]-nx)**2+(obstacles[:,1]-ny)**2<.52**2).any():
                        vx=vy=wz=0;self.native_blocked=True
                self.actual_velocity=(vx,vy,wz)
                if self.native_blocked:self.native_blocks+=1
                self.position[0]+=(vx*math.cos(self.yaw)-vy*math.sin(self.yaw))*dt
                self.position[1]+=(vx*math.sin(self.yaw)+vy*math.cos(self.yaw))*dt
                self.yaw+=wz*dt
                if now>=next_frame:
                    self.update_sensors();self.camera.simulate();next_frame=now+.10
                await asyncio.sleep(.02)
        finally:
            self.camera.disconnect()
            self.audio.disconnected()
            self.desired=(0,0,0);self.conn=None;self.lidar_enabled=False
            self.motion.revoke();self.avoidance.revoke();self.phase='disconnected'

    async def motion_mode(self):return {'code':0,'mode':self.current_motion_mode}
    async def normal_mode(self):return {'error':'SIMULATION_MODE_FIXED'}
    async def scan(self, enabled):
        self.lidar_enabled=enabled
        if enabled:self.update_sensors(force=True)
        return await self.lidar_snapshot()
    def move_once(self, vector):
        self.desired=vector;self.last_command=time.monotonic();self.last_move_code=0
    async def stop_move(self):self.desired=(0,0,0);self.last_stop_code=0
    def install_transport_guard(self):raise RuntimeError('SIMULATOR_HAS_NO_REAL_TRANSPORT')


async def serve():
    bridge=SimBridge()
    loop=asyncio.get_running_loop()
    server=ThreadingHTTPServer(('127.0.0.1',PORT),handler_class(bridge,loop))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    bridge.monitor_task=asyncio.create_task(bridge.monitor())
    stopped=asyncio.Event()
    for sig in (signal.SIGINT,signal.SIGTERM):loop.add_signal_handler(sig,stopped.set)
    print(f'Go2 SIMULATION ONLY http://127.0.0.1:{PORT}',flush=True)
    try:await stopped.wait()
    finally:
        await bridge.command('disconnect')
        bridge.monitor_task.cancel()
        await asyncio.to_thread(bridge.recorder.stop)
        server.shutdown();server.server_close()


if __name__=='__main__':
    if len(sys.argv)!=1:raise SystemExit('Simulator accepts no robot address or real-mode options')
    def deny_outbound(event,args):
        if event in {'socket.connect','socket.sendto','socket.getaddrinfo'}:
            raise RuntimeError('SIMULATOR_OUTBOUND_NETWORK_FORBIDDEN')
    sys.addaudithook(deny_outbound)
    asyncio.run(serve())

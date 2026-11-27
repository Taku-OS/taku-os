"""Bounded, observed-floor grid for the flat-floor simulator, NOT real SLAM."""
import heapq
import math
import numpy as np

RESOLUTION = .1
RADIUS = .70
OFFSETS = [(x,y) for x in range(-8,9) for y in range(-8,9) if math.hypot(x,y)*RESOLUTION <= RADIUS+.071]


def cell(point): return tuple(round(v/RESOLUTION) for v in point[:2])
def point(key): return [round(v*RESOLUTION,3) for v in key]


class Grid:
    def __init__(self, data, center=(0.,0.)):
        self.bounds=[center[0]-3,center[0]+3,center[1]-3,center[1]+3]
        values=np.frombuffer(data,dtype='<f4').reshape(-1,3)
        values=values[np.isfinite(values).all(axis=1)]
        values=values[(np.abs(values[:,0]-center[0])<=3)&(np.abs(values[:,1]-center[1])<=3)]
        floor={cell(p) for p in values if abs(p[2])<.04}
        obstacles={cell(p) for p in values if .06<p[2]<.8}
        self.obstacles=obstacles
        # An absent floor observation remains unknown, including room edges.
        self.free={p for p in floor if all((p[0]+x,p[1]+y) in floor and (p[0]+x,p[1]+y) not in obstacles for x,y in OFFSETS)}

    def line(self,a,b,margin=0):
        distance=math.dist(a,b);steps=max(1,math.ceil(distance/.03))
        return all(cell([a[0]+(b[0]-a[0])*i/steps+dx,a[1]+(b[1]-a[1])*i/steps+dy]) in self.free
                   for i in range(steps+1) for dx,dy in ((0,0),(margin,margin),(-margin,margin),(margin,-margin),(-margin,-margin)))

    def route(self,start,target):
        start_key,end=cell(start),cell(target)
        if start_key not in self.free: raise ValueError('START_BLOCKED')
        if end not in self.free: raise ValueError('TARGET_UNKNOWN_OR_BLOCKED')
        frontier=[(0,start_key)];cost={start_key:0};previous={}
        while frontier:
            _,current=heapq.heappop(frontier)
            if current==end: break
            for dx,dy in ((1,0),(-1,0),(0,1),(0,-1),(1,1),(1,-1),(-1,1),(-1,-1)):
                nxt=(current[0]+dx,current[1]+dy)
                if nxt not in self.free: continue
                if dx and dy and ((current[0]+dx,current[1]) not in self.free or (current[0],current[1]+dy) not in self.free): continue
                value=cost[current]+math.hypot(dx,dy)
                if value>=cost.get(nxt,float('inf')): continue
                cost[nxt]=value;previous[nxt]=current
                heapq.heappush(frontier,(value+math.dist(nxt,end),nxt))
        if end not in cost: raise ValueError('NO_ROUTE')
        keys=[end]
        while keys[-1]!=start_key: keys.append(previous[keys[-1]])
        route=[point(k) for k in reversed(keys)]
        route[0]=list(start[:2]);route[-1]=list(target[:2])
        if any(not self.line(a,b) for a,b in zip(route,route[1:])): raise ValueError('NO_ROUTE')
        return route

    def approach(self,start,target):
        preferred=math.atan2(start[1]-target[1],start[0]-target[0])
        for offset in [0,.3,-.3,.6,-.6,1.,-1.,1.5,-1.5,math.pi]:
            p=[target[0]+.9*math.cos(preferred+offset),target[1]+.9*math.sin(preferred+offset)]
            try: self.route(start,p);return [round(v,3) for v in p]
            except ValueError: pass
        raise ValueError('NO_APPROACH_ROUTE')

    def snapshot(self):
        return {'resolution':RESOLUTION,'radius':RADIUS,'free':[point(p) for p in sorted(self.free)],
                'obstacles':[point(p) for p in sorted(self.obstacles)],'bounds':self.bounds,
                'scope':'simulation-observed-flat-floor'}

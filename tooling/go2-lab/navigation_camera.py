"""Pinhole rendering of the navigation test room; geometry is shared with simulation.
No target coordinates or identities are included in the AI observation metadata.
"""
import math
import numpy as np


def render(position,yaw,outdoor=False,obstacle=None):
    pixels=np.empty((360,640,3),dtype=np.uint8)
    pixels[:180]=[191,204,208];pixels[180:]=[145,151,143]
    c,s=math.cos(yaw),math.sin(yaw)
    def project(p):
        dx,dy=p[0]-position[0],p[1]-position[1]
        forward,left=dx*c+dy*s,-dx*s+dy*c
        if forward<.1:return None
        return (320-512*left/forward,180+512*(.38-p[2])/forward)
    def polygon(points,color):
        q=[project(p) for p in points]
        if any(p is None for p in q):return
        # Bounded scanline fill also clips geometry that extends off screen.
        for y in range(max(0,int(min(p[1] for p in q))),min(360,int(max(p[1] for p in q))+1)):
            xs=[]
            for a,b in zip(q,q[1:]+q[:1]):
                if min(a[1],b[1])<=y+.5<max(a[1],b[1]):xs.append(a[0]+(y+.5-a[1])*(b[0]-a[0])/(b[1]-a[1]))
            xs.sort()
            for left,right in zip(xs[::2],xs[1::2]):
                pixels[y,max(0,min(640,int(left))):max(0,min(640,int(right)+1))]=color
    def face(x,y,width,z0,z1,color):
        polygon([(x,y-width/2,z0),(x,y+width/2,z0),(x,y+width/2,z1),(x,y-width/2,z1)],color)
    if outdoor:
        # Synthetic flat ground and the same cardboard box as the rolling LiDAR scene.
        for distance in (1,2,3,4,6,10):
            y=max(180,min(359,int(180+512*.38/distance)))
            pixels[y:y+1]=[120,132,120]
        if obstacle is not None:
            x,y=obstacle
            polygon([(x-.12,y-.12,0),(x-.12,y+.12,0),(x-.12,y+.12,.3),(x-.12,y-.12,.3)],[185,137,78])
            polygon([(x-.12,y-.12,.3),(x+.12,y-.12,.3),(x+.12,y+.12,.3),(x-.12,y+.12,.3)],[213,172,109])
            polygon([(x+.12,y-.12,0),(x+.12,y+.12,0),(x+.12,y+.12,.3),(x+.12,y-.12,.3)],[185,137,78])
        return pixels
    # Back-wall doorway, recessed dark panel, lintel and a visible handle.
    face(2.5,1.3,.72,0,1.8,[88,66,51]);face(2.49,1.3,.58,0,1.7,[131,94,62])
    face(2.48,1.12,.045,.72,.77,[228,213,139])
    # Table at the right of the room. Four legs and a rectangular top.
    for x in (1.5,1.9):
        for y in (-1.25,-1.7):face(x,y,.07,0,.65,[75,58,42])
    polygon([(1.45,-1.2,.65),(1.95,-1.2,.65),(1.95,-1.75,.65),(1.45,-1.75,.65)],[164,111,61])
    # Cardboard box is the same .9..1.0 x -.1...1 obstacle in the cloud.
    polygon([(.9,-.1,0),(.9,.1,0),(.9,.1,.3),(.9,-.1,.3)],[185,137,78])
    polygon([(.9,-.1,.3),(.9,.1,.3),(1.,.1,.3),(1.,-.1,.3)],[213,172,109])
    return pixels

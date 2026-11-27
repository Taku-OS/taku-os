"""Synthetic Mac geometry timing only; no networking or robot transport."""
import json
import platform
import time
import numpy as np
from perception import geometry, sport_pose


def benchmark(count):
    # All points survive the height/range filters; none permits early exit.
    theta = np.linspace(0, 2*np.pi, count, endpoint=False)
    points = np.column_stack((2*np.cos(theta), 2*np.sin(theta), np.full(count,.32))).astype('<f4')
    cloud = {'received':10,'values':{'frameId':'odom','pointBytes':points.tobytes()}}
    pose = {'received':10,'values':sport_pose({'position':[0,0,.32],
        'imu_state':{'quaternion':[1,0,0,0]}},'odom',True)}
    samples = []
    for index in range(105):
        start = time.perf_counter()
        result = geometry(cloud,pose,10)
        elapsed = (time.perf_counter()-start)*1000
        assert result['valid'] and result['analyzedPoints']==count
        assert all(value=='clear' for value in result['directions'].values())
        if index>=5:samples.append(elapsed)
    return {'points':count,'samples':len(samples),'medianMs':round(float(np.median(samples)),3),
            'p95Ms':round(float(np.percentile(samples,95)),3),'maxMs':round(max(samples),3)}


if __name__=='__main__':
    print(json.dumps({'kind':'synthetic-geometry-only','platform':platform.platform(),
                      'results':[benchmark(n) for n in (48000,64000)],
                      'excludes':['sensor acquisition','network','decode','HTTP','render','robot braking']},indent=2))

"""Slow, real-time simulation acceptance: full route with a turn and a box. No network."""
import asyncio
import json
import math
from pathlib import Path
import time
from simulator import SimBridge
from geo_reference import GeoReference


async def main():
    bridge=SimBridge()
    reference=GeoReference([116.4,39.9],[0,0],0,90,'test','odom','simulation')
    route=[[0,0],[2.5,0],[2.5,1.5]]
    start=time.monotonic()
    max_deviation=0.
    try:
        nav=await bridge.navigation.command('geo-prepare',{
            'points':[reference.to_geo(p) for p in route],'name':'完整转弯路线验收','scope':'full'})
        assert not nav.get('requestError'),nav.get('requestError')
        ids={'mapId':nav['mapId'],'routeId':nav['geo']['routeId']}
        nav=await bridge.navigation.command('geo-start',ids)
        assert not nav.get('requestError'),nav.get('requestError')
        while bridge.navigation.active():
            await asyncio.sleep(.5)
            nav=await bridge.navigation.command('geo-heartbeat',ids)
            max_deviation=max(max_deviation,nav['geo']['crossTrack'])
            assert time.monotonic()-start<180,'Completion timeout'
        nav=bridge.navigation.snapshot()
        assert nav['state']=='arrived',nav.get('reason')
        assert nav['stopConfirmed'] and bridge.desired==(0,0,0)
        assert bridge.native_blocks==0
        distance=math.dist(bridge.position[:2],route[-1])
        assert distance<.16,distance
        result={'source':'simulation','scope':'full','routeMeters':nav['geo']['length'],
                'progressMeters':nav['geo']['progress'],'elapsedSeconds':time.monotonic()-start,
                'finalDistanceMeters':distance,'maxCrossTrackMeters':max_deviation,
                'physicalEnvelopeBlocks':bridge.native_blocks,'state':nav['state'],
                'stopConfirmed':nav['stopConfirmed'],'realRobotVerified':False}
        out=Path(__file__).resolve().parents[2]/'out/go2-lab/geo-verification'
        out.mkdir(parents=True,exist_ok=True)
        (out/'full-turn-results.json').write_text(json.dumps(result,indent=2))
        print(json.dumps(result),flush=True)
    finally:
        await bridge.navigation.stop()
        await bridge.command('disconnect')


if __name__=='__main__':asyncio.run(main())

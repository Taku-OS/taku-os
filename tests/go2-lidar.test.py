import sys
from pathlib import Path
import unittest
import json
import struct
import asyncio
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'))
from lidar import point_cloud, encode_frame
from protocol import permitted, LIDAR_TOPIC
from bridge import Bridge

class LidarTests(unittest.TestCase):
    def test_only_sensor_enable_and_subscription_allowed(self):
        self.assertTrue(permitted({'type':'msg','topic':'rt/utlidar/switch','data':'on'}))
        for value in ['off', {}, 'stand', 1]:
            self.assertFalse(permitted({'type':'msg','topic':'rt/utlidar/switch','data':value}))
        self.assertTrue(permitted({'type':'subscribe','topic':LIDAR_TOPIC}))
        self.assertTrue(permitted({'type':'rtc_inner_req','data':{'req_type':'disable_traffic_saving','instruction':'on'}}))
        self.assertFalse(permitted({'type':'rtc_inner_req','data':{'req_type':'disable_traffic_saving','instruction':'off'}}))
        for topic in ['rt/api/sport/request','rt/api/obstacles_avoid/request','rt/lowcmd']:
            self.assertFalse(permitted({'type':'req','topic':topic,'data':{'api_id':1003}}))
    def test_full_cloud_preserves_every_point_in_order(self):
        points=np.arange(18000).reshape(-1,3)/100
        cloud=point_cloud({'data':{'points':points},'frame_id':'odom','stamp':{'sec':1}})
        self.assertEqual(cloud['displayCount'],6000)
        self.assertEqual(cloud['pointCount'],6000)
        actual=np.frombuffer(cloud['pointBytes'],dtype='<f4').reshape(-1,3)
        self.assertEqual(len(actual),6000)
        np.testing.assert_allclose(actual,points,atol=0.00001)
        self.assertEqual(cloud['invalidCount'],0)
        self.assertEqual(cloud['frameId'],'odom')
    def test_binary_frame_alignment_and_exact_point_payload(self):
        points=np.arange(135000).reshape(-1,3)/1000
        cloud=point_cloud({'data':{'points':points}})
        raw=cloud.pop('pointBytes')
        packet=encode_frame({'values':cloud,'fresh':True},raw)
        length=struct.unpack_from('<I',packet)[0]
        self.assertEqual((length+4)%4,0)
        header=json.loads(packet[4:4+length])
        self.assertTrue(header['hasPoints'])
        self.assertEqual(header['values']['displayCount'],45000)
        actual=np.frombuffer(packet,offset=4+length,dtype='<f4').reshape(-1,3)
        np.testing.assert_allclose(actual,points,atol=0.00001)
        self.assertEqual(len(packet)-4-length,45000*12)
        same=encode_frame({'values':cloud})
        header_length=struct.unpack_from('<I',same)[0]
        self.assertEqual(len(same),4+header_length)
        self.assertFalse(json.loads(same[4:])['hasPoints'])

    def test_invalid_and_empty_are_not_clear_path_claims(self):
        cloud=point_cloud({'data':{'points':[]}})
        self.assertEqual(cloud['pointCount'],0)
        self.assertNotIn('safe',cloud)
        cloud=point_cloud({'data':{'points':[[1,2,3],[float('nan'),0,1]]}})
        self.assertEqual(cloud['pointCount'],1)
        with self.assertRaises(ValueError): point_cloud({'data':{'points':[[1,2]]}})
        with self.assertRaises(ValueError): point_cloud({'data':{'points':[[1e10,2,3]]}})

    def test_frame_keys_do_not_depend_on_coarse_sensor_timestamp(self):
        async def check():
            bridge=Bridge('127.0.0.1')
            bridge.lidar_enabled=True
            message={'data':{'stamp':1792848000.0,'data':{'points':[[1,2,3]]}}}
            bridge.receive_lidar(message)
            first=await bridge.lidar_snapshot()
            json.dumps(first)
            packet=await bridge.lidar_frame(first['frameKey'])
            self.assertFalse(json.loads(packet[4:])['hasPoints'])
            bridge.receive_lidar(message)
            second=await bridge.lidar_snapshot()
            self.assertNotEqual(first['frameKey'],second['frameKey'])
            packet=await bridge.lidar_frame(first['frameKey'])
            length=struct.unpack_from('<I',packet)[0]
            self.assertTrue(json.loads(packet[4:4+length])['hasPoints'])
            self.assertEqual(len(packet)-4-length,12)
            self.assertNotEqual(bridge.stream_id,Bridge('127.0.0.1').stream_id)
        asyncio.run(check())

if __name__ == '__main__': unittest.main()

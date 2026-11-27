import asyncio
import base64
import hashlib
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from audio_hub import AudioHub, TOPIC


class HubTests(unittest.IsolatedAsyncioTestCase):
    async def test_upload_then_lookup_by_content_name_without_deleting_existing_files(self):
        raw=b'RIFF'+b'x'*9000;calls=[];blocks=[];saved=[];progress=[]
        async def rpc(api,value=None,topic=None):
            self.assertEqual(topic,TOPIC);calls.append(api)
            if api==1001:return {'audio_list':[{'CUSTOM_NAME':'user-recording','UNIQUE_ID':'private'},*saved]}
            if api==2001:
                blocks.append(value['block_content'])
                self.assertEqual(value['file_md5'],hashlib.md5(raw).hexdigest())
                if value['current_block_index']==value['total_block_number']:
                    saved.append({'CUSTOM_NAME':value['file_name'],'UNIQUE_ID':'new-clip'})
            return {}
        hub=AudioHub(rpc)
        self.assertEqual(await hub.ensure(raw,lambda:True,progress.append),'new-clip')
        self.assertEqual(base64.b64decode(''.join(blocks)),raw)
        self.assertEqual(progress[-1],100)
        count=len(blocks);await hub.ensure(raw,lambda:True,progress.append)
        self.assertEqual(len(blocks),count)
        await hub.play('new-clip',lambda:True);await hub.stop()
        self.assertEqual(calls[-3:],[1007,1002,1003])
        self.assertNotIn(1009,calls)

    async def test_cancel_during_upload_never_plays(self):
        active=True;calls=[]
        async def rpc(api,value=None,topic=None):
            nonlocal active
            calls.append(api)
            if api==2001:active=False
            return {'audio_list':[]}
        hub=AudioHub(rpc)
        with self.assertRaises(asyncio.CancelledError):await hub.ensure(b'x'*12000,lambda:active,lambda _:None)
        self.assertEqual(calls,[1001,2001])
        with self.assertRaises(asyncio.CancelledError):await hub.play('clip',lambda:False)
        self.assertNotIn(1002,calls)

    async def test_upload_requires_device_library_confirmation(self):
        async def rpc(api,value=None,topic=None):return {'audio_list':[]}
        with self.assertRaisesRegex(RuntimeError,'AUDIO_NOT_SAVED'):
            await AudioHub(rpc).ensure(b'x',lambda:True,lambda _:None)

if __name__=='__main__':unittest.main()

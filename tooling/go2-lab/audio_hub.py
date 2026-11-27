"""Go2's acknowledged file player; never deletes or overwrites user recordings."""
import asyncio
import base64
import hashlib
import time

TOPIC = 'rt/api/audiohub/request'


class AudioHub:
    def __init__(self, rpc):
        self.rpc = rpc

    async def call(self, api, value=None):
        if api not in {1001, 1002, 1003, 1007, 2001}: raise ValueError('INVALID_AUDIO_API')
        return await self.rpc(api, value, topic=TOPIC)

    async def ensure(self, raw, current, progress):
        name = 'Taku_' + hashlib.sha256(raw).hexdigest()[:24]
        async def lookup():
            result = await self.call(1001)
            for item in result.get('audio_list', []):
                if item.get('CUSTOM_NAME') == name and isinstance(item.get('UNIQUE_ID'), str):
                    return item['UNIQUE_ID']
            return None
        identity = await lookup()
        if identity: return identity
        encoded = base64.b64encode(raw).decode('ascii')
        chunks = [encoded[i:i+4096] for i in range(0, len(encoded), 4096)]
        checksum = hashlib.md5(raw).hexdigest()  # Device transfer checksum, not authentication.
        created = int(time.time()*1000)
        for index, chunk in enumerate(chunks, 1):
            if not current(): raise asyncio.CancelledError()
            await self.call(2001, {'file_name': name, 'file_type': 'wav', 'file_size': len(raw),
                'current_block_index': index, 'total_block_number': len(chunks),
                'block_content': chunk, 'current_block_size': len(chunk), 'file_md5': checksum,
                'create_time': created})
            progress(round(index/len(chunks)*100))
            await asyncio.sleep(.02)
        for _ in range(5):
            if not current(): raise asyncio.CancelledError()
            identity = await lookup()
            if identity: return identity
            await asyncio.sleep(.2)
        raise RuntimeError('AUDIO_NOT_SAVED')

    async def play(self, identity, current):
        if not current(): raise asyncio.CancelledError()
        await self.call(1007, {'play_mode': 'no_cycle'})
        if not current(): raise asyncio.CancelledError()
        await self.call(1002, {'unique_id': identity})

    async def stop(self):
        await self.call(1003)

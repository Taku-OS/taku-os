import net from 'node:net';

export function localDevice(request) {
  if (request.target && request.target !== 'local') throw new Error('LocalDeviceBackend requires target=local');
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({path: '\0taku-godmode'});
    let response = '';
    socket.setTimeout(35000, () => socket.destroy(new Error('local device timeout')));
    socket.on('connect', () => socket.write(`${JSON.stringify({operation: 'local', arguments: {...request, target: 'local'}})}\n`));
    socket.on('data', chunk => {
      response += chunk.toString();
      if (response.length > 2_000_000) socket.destroy(new Error('response too large'));
      if (response.includes('\n')) { socket.end(); try { resolve(JSON.parse(response.split('\n')[0])); } catch (error) { reject(error); } }
    });
    socket.on('error', reject);
  });
}

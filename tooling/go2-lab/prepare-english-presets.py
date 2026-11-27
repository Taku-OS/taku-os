#!/usr/bin/env python3
"""Cut a generated English take into attributed offline presets.

Inputs: a Qwen VoiceDesign WAV and reviewed Whisper word timestamps.
The recording's text and voice description live in english-voice-recipe.json.
This authoring tool uses the existing Go2 PyAV/NumPy environment; playback
does not require speech synthesis, transcription, or Internet access.
"""
import argparse
import difflib
import hashlib
import io
import json
from pathlib import Path
import re
import wave

import numpy as np
from audio_control import normalize


def token(word):
    return re.sub(r'[^a-z0-9]', '', word.lower()).replace('taco', 'taku')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--take', type=Path, required=True)
    parser.add_argument('--alignment', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent / 'audio-presets'
    recipe = json.loads((directory / 'english-voice-recipe.json').read_text())
    recognized = json.loads(args.alignment.read_text())
    words = [w for s in recognized['segments'] for w in s['words']]
    expected = [token(w) for c in recipe['clips'] for w in c['text'].split()]
    actual = [token(w['word']) for w in words]
    alignment = difflib.SequenceMatcher(None, expected, actual, autojunk=False)
    if alignment.ratio() < .94:
        raise ValueError('Transcript differs too much: review the generated take')
    mapping = {}
    for op, a, b, c, d in alignment.get_opcodes():
        if op == 'equal':
            mapping.update({a+i: c+i for i in range(b-a)})
        elif op == 'replace' and d > c:
            mapping.update({i: min(d-1, c+int((i-a)*(d-c)/(b-a))) for i in range(a,b)})
    ranges, cursor = [], 0
    for clip in recipe['clips']:
        count = len(clip['text'].split())
        if cursor not in mapping or cursor+count-1 not in mapping:
            raise ValueError('Phrase boundary missing: ' + clip['id'])
        ranges.append((mapping[cursor], mapping[cursor+count-1]))
        cursor += count
    raw = args.take.read_bytes()
    pcm, duration = normalize(raw)
    with wave.open(io.BytesIO(pcm)) as source:
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype='<i2').astype(np.float32)
    boundaries = [0]
    for (_, previous_end), (next_start, _) in zip(ranges, ranges[1:]):
        left, right = words[previous_end]['end'], words[next_start]['start']
        if right-left < .08:
            raise ValueError('No clear phrase gap; review manually')
        # Choose a quiet 20ms window inside the recognized inter-phrase gap.
        candidates = range(int((left+.02)*48000), int((right-.02)*48000), 240)
        boundary = min(candidates, key=lambda i: float(np.mean(samples[i-480:i+480]**2)))
        boundaries.append(boundary)
    boundaries.append(len(samples))
    catalog_path = directory / 'catalog.json'
    old = json.loads(catalog_path.read_text())['clips']
    generated, report = [], []
    source_hash = hashlib.sha256(raw).hexdigest()
    for i, clip in enumerate(recipe['clips']):
        chunk = samples[boundaries[i]:boundaries[i+1]]
        peak = float(np.max(np.abs(chunk)))
        if peak < 32 or not .4 < len(chunk)/48000 < 15:
            raise ValueError('Invalid audio chunk: ' + clip['id'])
        chunk = np.pad(chunk*(16056/peak), (9600,9600)).astype('<i2')
        target = directory / (clip['id'] + '.wav')
        with wave.open(str(target), 'wb') as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(48000)
            wav.writeframes(chunk.tobytes())
        a,b = ranges[i]
        cut = {'start':round(boundaries[i]/48000,3), 'end':round(boundaries[i+1]/48000,3)}
        generated.append({
            'id':clip['id'], 'file':target.name, 'category':'speech',
            'language':'en-US', 'synthetic':True,
            'title':{'zh':clip['title'], 'en':clip['title']},
            'description':{'zh':clip['zh'], 'en':'Original phrase · Conversational English'},
            'transcript':clip['text'], 'voiceStyle':recipe['style'],
            'duration':round(len(chunk)/48000,3),
            'author':'Taku · AI-generated original voice',
            'license':'Original generated audio; model licensed Apache-2.0',
            'licenseUrl':'https://www.apache.org/licenses/LICENSE-2.0',
            'source':'https://huggingface.co/spaces/Qwen/Qwen3-TTS',
            'changes':{
                'zh':'原创台词合成；从同一次录音切分；48kHz 单声道 PCM WAV；峰值 -6.2dBFS；首尾各补 0.2 秒静音',
                'en':'Original script synthesized in one take and split at phrase gaps; 48kHz mono PCM WAV; -6.2dBFS peak; 0.2s padding at each end',
            },
            'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
            'sourceSha256':source_hash,
            'generation':{'model':recipe['model'], 'sourceTakeRange':cut},
        })
        report.append({'id':clip['id'], 'expected':clip['text'], 'recognized':''.join(w['word'] for w in words[a:b+1]).strip(), 'sourceRange':cut, 'duration':generated[-1]['duration']})
    ids = {c['id'] for c in generated}
    catalog = {'version':2, 'clips':generated+[c for c in old if c['id'] not in ids]}
    temporary = catalog_path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2)+'\n')
    temporary.replace(catalog_path)
    args.report.write_text(json.dumps({'sourceSha256':source_hash, 'sourceDuration':duration, 'transcriptMatchRatio':alignment.ratio(), 'clips':report},ensure_ascii=False,indent=2)+'\n')
    print(f'Prepared {len(generated)} English presets; {len(catalog["clips"])} total')


if __name__ == '__main__':
    main()

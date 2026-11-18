#!/usr/bin/env python3
"""Real Android call-playback route with a synthetic source; no cloud or physical call."""
import argparse
import json
from pathlib import Path
import subprocess
import time

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--adb',default=str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
p.add_argument('--output',required=True,type=Path)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*cmd):return subprocess.check_output([a.adb,'-s',a.serial,*cmd],text=True).strip()
assert a.serial.startswith('emulator-') and adb('emu','avd','name').splitlines()[0].startswith('Taku_Audio_ROM_')
reports=[]
try:
    for reason in ['QUEUE_BACKLOG','CAPTURE_FAILED','CALL_ENDED']:
        adb('shell','am','force-stop','org.taku.callfixture.target')
        adb('shell','am','start','-W','-n','org.taku.callfixture.target/org.taku.callfixture.CallFixture')
        time.sleep(.5)
        command=[a.adb,'-s',a.serial,'shell','am','instrument','-w','-e','mode','captureFailure','-e','expected',reason,'org.agentos.audio.tests/.ReviewRegressionProbe']
        # The probe bounds its wait to 10 s; terminate the synthetic owner only
        # after the actual policy/AudioRecord has been created.
        with subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True) as process:
            lines=[]
            for line in process.stdout:
                lines.append(line)
                if reason=='CALL_ENDED' and 'phase=route-active' in line:
                    adb('shell','am','force-stop','org.taku.callfixture.target')
            process.wait(timeout=20)
        result=''.join(lines);(a.output/(reason+'.txt')).write_text(result)
        report=json.loads(next(line.split('report=',1)[1] for line in lines if line.startswith('INSTRUMENTATION_RESULT: report=')))
        assert report['passed'], report
        reports.append(report)
    (a.output/'capture.json').write_text(json.dumps({'passed':True,'reports':reports,'physicalCallVerified':False},indent=2)+'\n')
finally:
    adb('shell','am','force-stop','org.taku.callfixture.target')

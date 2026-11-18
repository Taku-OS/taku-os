#!/usr/bin/env python3
"""Record the verified native widget test actions; never submit a model task.

Uses accessibility nodes instead of layout-dependent coordinates. The initial
Home selection is off, and the tested final selection is off.
"""
import os
import pathlib,subprocess,time,json
BASE=pathlib.Path(__file__).resolve().parents[1];OUT=BASE/'evidence/home-widget'
ADB=os.environ.get('ADB', 'adb');SERIAL='emulator-5582';REMOTE='/data/local/tmp/taku-widget-flow-verified.mp4'
assert subprocess.run([ADB,'-s',SERIAL,'emu','avd','name'],check=True,capture_output=True,text=True).stdout.splitlines()[0]=='AgentOS_ImageCheck'
OUT.mkdir(parents=True,exist_ok=True)
record=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--size','720x1600','--bit-rate','5000000','--time-limit','22',REMOTE],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
try:
 time.sleep(.5)
 test=subprocess.run(['bash',str(BASE/'test-widget-home.sh')],cwd=BASE,capture_output=True,text=True,timeout=48)
 (OUT/'record-test-output.txt').write_text(test.stdout+test.stderr)
 assert test.returncode==0,(test.returncode,test.stdout,test.stderr)
 record.communicate(timeout=25);assert record.returncode==0
 subprocess.run([ADB,'-s',SERIAL,'pull',REMOTE,str(OUT/'widget-flow.mp4')],check=True,capture_output=True)
 probe=subprocess.run([os.environ.get('FFPROBE', 'ffprobe'),'-v','error','-show_entries','format=duration,size','-show_entries','stream=width,height,codec_name','-of','json',str(OUT/'widget-flow.mp4')],check=True,capture_output=True,text=True)
 (OUT/'widget-flow-metadata.json').write_text(probe.stdout)
 print(OUT/'widget-flow.mp4')
finally:
 if record.poll() is None:
  record.terminate();record.communicate(timeout=5)

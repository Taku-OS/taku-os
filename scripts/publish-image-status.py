#!/usr/bin/env python3
"""Publish only build-stage fields, never raw build logs, environment or credentials."""
import argparse,datetime,fcntl,json,os,pathlib,subprocess,sys,time
from image_build_config import ROOT,CONFIG,SOURCE_LOCK,OUT,NAME,REPORT
parser=argparse.ArgumentParser();parser.add_argument('--watch',action='store_true');args=parser.parse_args()
guard=None
if args.watch:
    guard=(OUT/'publication.lock').open('w')
    try:fcntl.flock(guard,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError:raise SystemExit(0)
    if sys.platform=='darwin':
        subprocess.Popen(['caffeinate','-i','-w',str(os.getpid())],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
while True:
    state_path=OUT/'status.json'
    source=json.loads(state_path.read_text()) if state_path.exists() else {}
    r=subprocess.run(['docker','inspect','--format','{{json .State}}',NAME],capture_output=True,text=True)
    container=json.loads(r.stdout) if r.returncode==0 else {}
    data={k:source[k] for k in ['phase','updatedAt','romBuilt','physicalDeviceVerified','coldBootVerified','signing','security','artifactSignaturesVerified','artifactsExported','exportDirectory'] if k in source}
    data.update({'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'target':'Pixel 9 / tokay','platform':SOURCE_LOCK['platform'],'variant':SOURCE_LOCK['variant'],'container':NAME,'running':container.get('Running',False),'exitCode':container.get('ExitCode'),'oomKilled':container.get('OOMKilled',False),'artifactCount':len(source.get('artifacts',[]))})
    dest=REPORT;dest.parent.mkdir(parents=True,exist_ok=True);tmp=dest.with_suffix('.tmp')
    tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');tmp.replace(dest)
    if not args.watch or not container.get('Running'):
        if args.watch and container.get('ExitCode')==0 and source.get('romBuilt'):
            # Complete the local delivery after the compiler exits successfully.
            import sys
            exported=subprocess.run([sys.executable,str(ROOT/'scripts/pixel9-image.py'),'export'])
            final_state=json.loads(state_path.read_text())
            data['artifactsExported']=final_state.get('artifactsExported',False)
            data['artifactSignaturesVerified']=final_state.get('artifactSignaturesVerified',False)
            if final_state.get('exportDirectory'):data['exportDirectory']=final_state['exportDirectory']
            tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');tmp.replace(dest)
        break
    time.sleep(30)

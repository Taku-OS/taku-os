from pathlib import Path
import sys
source=Path('system/proactivity/tests/verify-emulator.py').resolve()
sys.argv=[str(source),'--serial','emulator-5590','--expected-avd','Taku_Proactivity_20261024','--adb','/Users/haipro/Library/Android/sdk/platform-tools/adb','--out','out/proactivity-dashboard-20261024']
__file__=str(source)
exec(compile(source.read_text().split('\ntry:\n    check(adb(')[0],str(source),'exec'))
try:
    check(adb('shell','getprop','ro.kernel.qemu').strip()=='1','Emulator only')
    check(adb('emu','avd','name').splitlines()[0]==args.expected_avd,'Named dashboard fixture')
    check(installed_hash(HOST)==hashlib.sha256((args.out/'TakuProactivity.apk').read_bytes()).hexdigest(),'Installed final dashboard APK bytes')
    for package in (PKG,HOST):check('Success' in adb('shell','pm','clear',package),'Reset own dashboard fixture '+package)
    start(1);approve()
    first=offer(1,burst=3);accept(first)
    second=offer(1);accept(second)
    adb('shell','am','start','-W','-n',HOST+'/.ProactivityActivity')
    tap('tab-0',package=HOST);shot('overview')
    heat=next(n for n in nodes() if n.get('content-desc','').startswith('24 小时活动热力图'))
    check('提示 2，查看 2' in heat.get('content-desc',''),'Heatmap shows actual two publish and accept receipts')
    before=heat.get('content-desc');tap('前一小时',package=HOST);tap('前一小时',package=HOST)
    after=next(n for n in nodes() if n.get('content-desc','').startswith('24 小时活动热力图')).get('content-desc')
    check(before!=after and '提示 0，查看 0' in after,'Native hour selection changes to empty previous hour')
    trend=next(n for n in nodes() if n.get('content-desc','').startswith('提示趋势，每格两小时')).get('content-desc')
    check('展示 0，查看 0' in trend,'Trend selection follows heatmap')
    shot('selected-hour');tap('后一小时',package=HOST);tap('后一小时',package=HOST)
    # Scroll in the actual ScrollView, keeping the tabs and system navigation untouched.
    def swipe_up():
        frame=next(n for n in nodes() if n.get('class')=='android.widget.ScrollView');b=list(map(int,re.findall(r'\d+',frame.get('bounds'))));x=(b[0]+b[2])//2
        adb('shell','input','swipe',str(x),str(b[1]+(b[3]-b[1])*3//4),str(x),str(b[1]+(b[3]-b[1])//4),'320')
    swipe_up();shot('trend')
    swipe_up();shot('status-ranking')
    tap('tab-1',package=HOST);tap('observer-org.example.taku.observer.page',package=HOST);tap(text='调整',package=HOST)
    tap('policy-maxRecords',package=HOST);adb('shell','input','keyevent','KEYCODE_MOVE_END');adb('shell','input','keyevent','KEYCODE_DEL');adb('shell','input','text','5')
    adb('shell','settings','put','system','user_rotation','1')
    wait(lambda:int(re.search(r'cur=(\d+)x',adb('shell','dumpsys','window','displays')).group(1))>1080,'editor landscape')
    tap('policy-maxRecords',package=HOST)
    wait(lambda: any(n.get('text')=='5' and n.get('content-desc')=='最多参考记录（1–16 条）' for n in nodes()),'unsaved draft restored after rotation')
    check(True,'Unsaved N=5 survives actual activity recreation')
    box=node('policy-maxRecords',package=HOST);density=int(re.findall(r'density: (\d+)',adb('shell','wm','density'))[-1])/160
    check(box[3]-box[1]>=48*density,'Landscape IME leaves at least 48dp visible for the focused field')
    shot('editor-landscape-draft')
    adb('shell','input','keyevent','BACK');wait(lambda:node('取消',package=HOST),'Cancel returns after IME closes')
    tap('取消',package=HOST);adb('shell','settings','put','system','user_rotation','0')
    wait(lambda:int(re.search(r'cur=(\d+)x',adb('shell','dumpsys','window','displays')).group(1))==1080,'portrait restored')
    adb('shell','input','keyevent','BACK');tap('tab-0',package=HOST)
    tap('前一小时',package=HOST);tap('后一小时',package=HOST)
    adb('shell','settings','put','system','font_scale','1.3');wait(lambda:node('前一小时',package=HOST),'large font chart controls')
    shot('large-font');tap('前一小时',package=HOST);check(bool(node('后一小时',package=HOST)),'Large font hour controls reachable');tap('后一小时',package=HOST)
    adb('shell','settings','put','system','user_rotation','1');wait(lambda:int(re.search(r'cur=(\d+)x',adb('shell','dumpsys','window','displays')).group(1))>1080,'dashboard landscape');shot('landscape')
    for index in range(3):check(bool(node('tab-'+str(index),package=HOST)),'Large font landscape tab '+str(index))
    adb('shell','settings','put','system','user_rotation','0');adb('shell','settings','put','system','font_scale','1.0')
    wait(lambda:int(re.search(r'cur=(\d+)x',adb('shell','dumpsys','window','displays')).group(1))==1080,'portrait final')
    # Last remove operation verifies truly empty data; no fake registry or trace insertion.
    tap('tab-1',package=HOST);tap('observer-org.example.taku.observer.page',package=HOST);tap('remove-observer',package=HOST);tap(text='移除',package=HOST);tap('tab-0',package=HOST);shot('empty')
    heat=next(n for n in nodes() if n.get('content-desc','').startswith('24 小时活动热力图'))
    check('策略 0，提示 0，查看 0，其他 0' in heat.get('content-desc',''),'Empty chart stays zero after actual removal')
    # Restore a real active observer for the final overview, with actual receipts.
    start(1);approve();last_offer=offer(1,burst=3);accept(last_offer)
    adb('shell','am','start','-W','-n',HOST+'/.ProactivityActivity');tap('tab-0',package=HOST);shot('overview-final')
    check('FATAL EXCEPTION' not in adb('logcat','-d','-s','AndroidRuntime:E'),'No runtime crash in dashboard acceptance')
    report={'passed':True,'at':datetime.now(timezone.utc).isoformat(),'serial':args.serial,'avd':args.expected_avd,'checks':checks,'hostApkSha256':installed_hash(HOST),'scope':'Native dashboard, real registry metadata, linked selection, empty data, large font, rotation and unsaved editor draft. Protocol/upgrade loop recorded separately.','first':first,'second':second,'final':last_offer,'screenshots':sorted(p.name for p in args.out.glob('*.png'))}
    (args.out/'dashboard-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':True,'checks':len(checks)}),flush=True)
except Exception as error:
    (args.out/'failure.json').write_text(json.dumps({'error':str(error),'checks':checks},ensure_ascii=False,indent=2));shot('failure');raise
finally:
    adb('shell','settings','put','system','user_rotation','0');adb('shell','settings','put','system','font_scale','1.0')

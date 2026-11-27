"""Sustained on-device scroll on the real zhanguo session, with out-of-process monitoring."""
import argparse,concurrent.futures,json,os,re,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('tty');p.add_argument('remote_dir');p.add_argument('--seconds',type=int,default=300);p.add_argument('--host',default='rpi5');a=p.parse_args()
adb=os.environ.get('ADB','adb')
pkg='com.sonelli.juicessh';activity=pkg+'.activities.TerminalEmulatorActivity'
trace='zgperf-juice-stress.pftrace';tracepath='/data/misc/perfetto-traces/'+trace
def run(v,timeout=15):
 r=subprocess.run(v,capture_output=True,timeout=timeout)
 return r.stdout.decode('utf8','replace').strip()
def sh(s):return run([adb,'shell',s])
def remote(s):return run(['ssh',a.host,s])
def focus():return sh('dumpsys window | grep mCurrentFocus')
def exitinfo():return sh('dumpsys activity exit-info '+pkg+' | head -30')
def pid():return sh('pidof '+pkg)
def client():return remote("tmux list-clients -F '#{client_tty} #{session_name} #{client_width}x#{client_height}' | grep -F '"+a.tty+" '")
def state():return remote("tmux display -pt zg-mixed '#{pane_in_mode}|#{scroll_position}|#{pane_width}|#{pane_height}'")
def save():Path('juice-stress.json').write_text(json.dumps(rec,ensure_ascii=False,indent=2),encoding='utf8')
remote('tmux switch-client -c '+a.tty+' -t zg-mixed');time.sleep(1)
startpid=pid()
if not startpid or activity not in focus() or 'zg-mixed 209x193' not in client():raise RuntimeError('stress starting conditions')
cfg='buffers { size_kb: 32768 fill_policy: RING_BUFFER }\nduration_ms: '+str((a.seconds+90)*1000)+'\ndata_sources { config { name: "android.surfaceflinger.frametimeline" } }\ndata_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }\n'
Path('stress.cfg').write_text(cfg);run([adb,'push','stress.cfg','/data/misc/perfetto-configs/zg-stress.cfg'])
bg=sh('perfetto --background-wait --txt -c /data/misc/perfetto-configs/zg-stress.cfg -o '+tracepath)
tracepid=re.search(r'(?m)^\s*(\d+)\s*$',bg)[1]
stamp=sh('date "+%m-%d %H:%M:%S.000"')
rec={'package':pkg,'start_device':stamp,'start_pid':startpid,'start_client':client(),'before_exit':exitinfo(),'start_pane':state(),'before_focus':focus(),'requested_seconds':a.seconds,'samples':[],'blocks':[]}
started=time.monotonic(); proc=None;why='duration reached'
def events():
 return sh("logcat -d -b events -T '"+stamp+"' -s am_anr am_crash am_proc_died | grep -F "+pkg+" | tail -10")
try:
 while time.monotonic()-started<a.seconds:
    proc=subprocess.Popen([adb,'shell','echo $$ >/data/local/tmp/zg-stress-driver.pid; export CLASSPATH=/data/local/tmp/zg-gesture.jar; exec app_process / Gesture swipe 628 900'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    while proc.poll() is None:
        f=focus();spid=pid();conn=client();evt=events()
        rec['samples'].append({'s':round(time.monotonic()-started,2),'pid':spid,'focus':f,'client':conn,'pane':state(),'events':evt})
        if evt or spid!=startpid or activity not in f or 'zg-mixed 209x193' not in conn:
            why='Android event, process, foreground or SSH client changed'
            sh('p=$(cat /data/local/tmp/zg-stress-driver.pid); if grep -azq Gesture /proc/$p/cmdline; then kill -TERM "$p"; fi')
            break
        time.sleep(.5)
    stdout,stderr=proc.communicate(timeout=12)
    rows=[]
    for l in stdout.decode(errors='replace').splitlines():
        fields=l.split()
        if len(fields)==4 and fields[0] in ('phase','stroke'):rows.append([fields[0],*map(int,fields[1:])])
    rec['blocks'].append({'elapsed':round(time.monotonic()-started,2),'times':rows,'exit':proc.returncode})
    save()
    if why!='duration reached' or proc.returncode:break
finally:
 rec['elapsed_input_period']=round(time.monotonic()-started,2)
 if why!='duration reached':time.sleep(6)
 rec.update(reason=why,after_pid=pid(),after_focus=focus(),after_client=client(),after_exit=exitinfo(),events=events(),end_pane=state())
 sh('kill -TERM '+tracepid+' 2>/dev/null || true');time.sleep(1)
 run([adb,'pull',tracepath,trace]);run(['scp',trace,a.host+':'+a.remote_dir+'/'+trace])
 if rec['start_pane'].startswith('0|') and rec['end_pane'].startswith('1|'):remote('tmux send-keys -t zg-mixed -X cancel')
 save()
 print(why,rec['elapsed_input_period'],len(rec['blocks']),rec['after_pid'],rec['after_focus'],flush=True)

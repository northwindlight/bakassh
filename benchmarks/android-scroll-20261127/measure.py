"""Run on the ADB controller from the work directory. POSIX ssh/scp and perfetto on device."""
import argparse,hashlib,json,os,re,shlex,subprocess,time
from pathlib import Path
P=argparse.ArgumentParser()
for name in ('label','package','tty','remote_dir'): P.add_argument(name)
P.add_argument('--targets',nargs='+',default=['zperf-fixed','zg-mixed','zperf-long-claude','zperf-long-codex'])
P.add_argument('--x',type=int,default=628); P.add_argument('--y',type=int,default=900)
args=P.parse_args()
adb=os.environ.get('ADB','adb')
host=os.environ.get('BENCH_HOST','rpi5')
out=Path(os.environ.get('BENCH_WORK','.'))
def cmd(argv,timeout=35):
    p=subprocess.run(argv,capture_output=True,timeout=timeout)
    if p.returncode: raise RuntimeError((argv,p.stderr.decode(errors='replace')[-500:]))
    return p.stdout.decode('utf8','replace').strip()
def shell(s):return cmd([adb,'shell',s])
def remote(s):return cmd(['ssh',host,s])
def snap(): return hashlib.sha256(subprocess.check_output([adb,'exec-out','screencap','-p'])).hexdigest()
def focus():return shell('dumpsys window | grep mCurrentFocus')
def mem():return shell('dumpsys meminfo '+args.package+' | grep -E "TOTAL PSS:|TOTAL RSS:"')
def write(): (out/(args.label+'.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf8')
trace='zgperf-'+args.label+'.pftrace'
device_trace='/data/misc/perfetto-traces/'+trace
record={'label':args.label,'package':args.package,'tty':args.tty,'gesture_xy':[args.x,args.y],'cases':[],'battery':shell('dumpsys battery | grep -E "temperature:|level:"'),'wm':shell('wm size; wm density'),'mem_before':mem()}
statefmt="'#{session_name} #{client_width}x#{client_height} #{client_tty}'"
paneformat="'#{pane_width}x#{pane_height} #{history_size} #{pane_in_mode} #{scroll_position}'"
if args.package not in focus():raise RuntimeError('target not foreground')
pidtxt=shell('perfetto --background-wait --txt -c /data/misc/perfetto-configs/zg-perf.cfg -o '+device_trace)
pid=re.search(r'(?m)^\s*(\d+)\s*$',pidtxt)
if not pid:raise RuntimeError(('trace start',pidtxt))
try:
 for t in args.targets:
    case={'target':t};record['cases'].append(case)
    remote('tmux switch-client -c '+shlex.quote(args.tty)+' -t '+shlex.quote(t))
    time.sleep(1)
    client=remote('tmux display-message -p -c '+args.tty+' '+statefmt)
    if not client.startswith(t+' '): raise RuntimeError(('target mismatch',client,t))
    if t=='zperf-fixed':
        remote('tmux resize-window -t zperf-fixed -x 200 -y 160 && tmux copy-mode -t zperf-fixed && tmux send-keys -t zperf-fixed -X history-bottom && tmux send-keys -t zperf-fixed -N 300 -X scroll-up')
    if 'long-' in t:
        # Start well inside the native transcript. The capability is checked from the displayed content.
        remote('tmux send-keys -t '+t+' End');time.sleep(.4)
        remote('tmux send-keys -t '+t+' PageUp PageUp');time.sleep(.6)
    time.sleep(.8)
    case.update(client=client,pane_before=remote('tmux display-message -p -t '+t+' '+paneformat),focus_before=focus(),screen_before=snap())
    if args.package not in case['focus_before']:raise RuntimeError('foreground lost')
    mon=subprocess.Popen(['ssh',host,'python3 '+shlex.quote(args.remote_dir)+'/monitor.py '+t],stdout=subprocess.PIPE)
    def anchors(seconds):
        return subprocess.Popen(['ssh',host,'python3 '+shlex.quote(args.remote_dir)+'/anchors.py '+t+' '+str(seconds)],stdout=subprocess.PIPE)
    moving=anchors(11) if 'long-' in t else None
    act=shell('CLASSPATH=/data/local/tmp/zg-gesture.jar app_process / Gesture swipe '+str(args.x)+' '+str(args.y))
    case['times']=[[z[0],*map(int,z[1:])] for z in (l.split() for l in act.splitlines()) if z and z[0] in ('phase','stroke')]
    case['screen_after']=snap();case['focus_after']=focus()
    case['monitor']=json.loads(mon.communicate(timeout=15)[0])
    if moving:
        case['content_touch']=json.loads(moving.communicate(timeout=15)[0])
        keys=anchors(7)
        time.sleep(.9)
        shell('for k in 92 92 92 93 93 93; do input keyevent $k; sleep .55; done')
        case['content_pagekeys']=json.loads(keys.communicate(timeout=12)[0])
    case['pane_after']=remote('tmux display-message -p -t '+t+' '+paneformat)
    if t.startswith('zperf-') and t!='zperf-lobby' and case['pane_after'].split()[2]=='1':
        remote('tmux send-keys -t '+t+' -X cancel')
    if t=='zg-mixed' and case['pane_before'].split()[2]=='0' and case['pane_after'].split()[2]=='1':
        remote('tmux send-keys -t zg-mixed -X cancel')
    if t in ('zperf-claude','zperf-codex') and args.package in case['focus_after']:
        marker='zgcheck42'
        shell('input text '+marker);time.sleep(.5)
        def visible():return remote('tmux capture-pane -pt '+t+' | grep -Fc '+marker+' || true')!='0'
        case['typed_marker']=visible()
        if case['typed_marker']:
            shell('input keyevent '+' '.join(['67']*len(marker)));time.sleep(.4)
            case['erase_ok']=not visible()
        else:case['erase_ok']=None
    write()
    print(args.label,t,case['client'],case['pane_before'],len(case['times']),case.get('typed_marker'),flush=True)
    if args.package not in case['focus_after']:raise RuntimeError('terminal lost focus')
finally:
    shell('kill -TERM '+pid[1]+' 2>/dev/null || true');time.sleep(1)
    record['mem_after']=mem()
    record['exit_info']=shell('dumpsys activity exit-info '+args.package+' | head -18')
    write()
    cmd([adb,'pull',device_trace,str(out/trace)])
    cmd(['scp',str(out/trace),host+':'+args.remote_dir+'/'+trace])

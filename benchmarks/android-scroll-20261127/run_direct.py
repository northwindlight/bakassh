import argparse,subprocess,shlex,json,time
from pathlib import Path
ap=argparse.ArgumentParser()
for n in ('tty','label','remote_dir'):ap.add_argument(n)
ap.add_argument('--host',default='rpi5');ap.add_argument('--cols',type=int,default=200);ap.add_argument('--rows',type=int,default=160)
args=ap.parse_args()
name=args.label+'-direct.json'
path=args.remote_dir+'/'+name
def remote(s):return subprocess.run(['ssh',args.host,s],capture_output=True)
if remote('test -e '+shlex.quote(path)).returncode==0:raise ValueError('choose a fresh label')
command=f'python3 {shlex.quote(args.remote_dir+"/direct.py")} {shlex.quote(path)} --cols {args.cols} --rows {args.rows}; exec tmux attach -t zperf-lobby'
r=remote('tmux detach-client -t '+shlex.quote(args.tty)+' -E '+shlex.quote(command))
r.check_returncode()
for _ in range(50):
 time.sleep(1)
 if remote('test -s '+shlex.quote(path)).returncode==0:break
else:raise TimeoutError(name)
subprocess.run(['scp',args.host+':'+path,name],check=True)
d=json.loads(Path(name).read_text())
print(d['pty'],{k:v['ok'] for k,v in d['probes'].items()},list(d['tests']),d.get('error'))

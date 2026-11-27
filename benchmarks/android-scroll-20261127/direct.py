"""Direct SSH PTY client-response benchmark. Run outside tmux, from the foreground."""
import argparse, hashlib, json, os, re, select, statistics, termios, time, tty
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('result');parser.add_argument('--cols',type=int,default=200);parser.add_argument('--rows',type=int,default=160)
args=parser.parse_args()
size=os.get_terminal_size(1)
record={'pty':[size.columns,size.lines],'canvas':[args.cols,args.rows],'term':os.getenv('TERM'),'probes':{},'tests':{}}
def emit(data):
    view=memoryview(data)
    while view: view=view[os.write(1,view):]
def cpr():
    emit(b'\x1b[6n')
    end=time.monotonic()+2
    buf=b''
    while time.monotonic()<end:
        if not select.select([0],[],[],max(0,end-time.monotonic()))[0]: break
        buf+=os.read(0,512)
        m=re.search(rb'\x1b\[\??(\d+);(\d+)R',buf)
        if m: return list(map(int,m.groups()))
    raise TimeoutError('no cursor position report in 2 s')
def probe(name,data,expect):
    emit(data); actual=cpr()
    record['probes'][name]=dict(expected=expect,actual=actual,ok=expect==actual)
def frame(kind,n):
    p=[]
    if kind=='scroll':
        p.append(f'\x1b[{args.rows};1H'.encode())
    for r in range(1,33 if kind=='scroll' else args.rows+1):
        if kind!='scroll':p.append(f'\x1b[{r};1H'.encode())
        i=(n*37+r)%10000
        content=(f'{i:04d} 齐楚秦赵 │ 粮草+{i%41:02d} ▂▄▆█  ASCII {r:03d} Δ ' * 6)
        # The repeated segment is 42 cells or more. Enforce a cell width independent of locale.
        import unicodedata
        width=0; parts=[]
        for ch in content:
            w=2 if unicodedata.east_asian_width(ch) in ('W','F') else 1
            if width+w>args.cols-1: break
            parts.append(ch);width+=w
        p.append((f'\x1b[38;5;{24+(n+r)%192}m'+''.join(parts)+'\x1b[0m').encode())
        if kind=='scroll':p.append(b'\r\n')
    p.append(b'\x1b[1;1H')
    return b''.join(p)
old=termios.tcgetattr(0)
try:
    tty.setraw(0)
    emit(b'\x1b[?1049h\x1b[0m\x1b[2J\x1b[H')
    probe('CUP',b'\x1b[7;11H',[7,11])
    probe('CJK', '\x1b[4;9H战国'.encode(),[4,13])
    probe('combining','\x1b[4;9He\u0301'.encode(),[4,10])
    probe('DECSC_DECRC',b'\x1b[5;9H\x1b7\x1b[12;3H\x1b8',[5,9])
    probe('scroll_region',b'\x1b[3;8r\x1b[8;1H\n',[8,1])
    emit(b'\x1b[r\x1b[2J\x1b[H')
    if size.columns < args.cols or size.lines < args.rows:raise ValueError('viewport too small for fixed payload')
    idle=[]
    for _ in range(14):
        a=time.perf_counter_ns();cpr();idle.append((time.perf_counter_ns()-a)/1e6)
    record['idle_cpr_ms']=idle[2:]
    for kind in ['scroll','rewrite']:
        # Constrain scroll region to the common payload height, including on taller terminals.
        emit(f'\x1b[1;{args.rows}r\x1b[2J\x1b[H'.encode());cpr()
        times=[];dig=hashlib.sha256();total=0
        for n in range(20):
            data=frame(kind,n)
            a=time.perf_counter_ns();emit(data);pos=cpr();duration=(time.perf_counter_ns()-a)/1e6
            if pos!=[1,1]:raise ValueError(('unexpected final cursor position',pos))
            if n>=2:times.append(duration);dig.update(data);total+=len(data)
        record['tests'][kind]={'ms':times,'total_bytes':total,'sha256':dig.hexdigest()}
except Exception as e: record['error']=str(e)
finally:
    emit(b'\x1b[r\x1b[0m\x1b[?1049l');termios.tcsetattr(0,termios.TCSANOW,old)
    Path(args.result).write_text(json.dumps(record,indent=2))

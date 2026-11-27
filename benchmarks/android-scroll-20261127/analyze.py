"""Aggregate only terminal Activity presentation timestamps. Needs trace_processor_shell on rpi5."""
import argparse,csv,io,json,math,os,shlex,statistics,subprocess
from pathlib import Path
ap=argparse.ArgumentParser()
ap.add_argument('record');ap.add_argument('trace');ap.add_argument('--host',default='rpi5');ap.add_argument('--tp',default='~/.local/share/perfetto/prebuilts/trace_processor_shell-*')
args=ap.parse_args()
data=json.loads(Path(args.record).read_text(encoding='utf-8-sig'))
app=data['package']
layer={'com.termux':'TermuxActivity','dev.northwind.bakassh':'MainActivity','org.connectbot':'MainActivity','com.sonelli.juicessh':'TerminalEmulatorActivity'}[app]
def query(sql):
    command=f'{args.tp} query {shlex.quote(args.trace)} {shlex.quote(sql)}'
    p=subprocess.run(['ssh',args.host,command],check=True,capture_output=True)
    return list(csv.DictReader(io.StringIO(p.stdout.decode())))
frames=query(f"select ts+dur t,present_type typ,jank_type j,layer_name layer from actual_frame_timeline_slice a join process p using(upid) where p.name='{app}' and dur>0 and surface_frame_token>0 order by t")
layers=sorted({f['layer'] for f in frames})
frames=[f for f in frames if layer.lower() in f['layer'].lower()]
def pct(values,p):
    if not values:return None
    a=sorted(values);i=(len(a)-1)*p;lo=math.floor(i)
    return round(a[lo]+(a[min(lo+1,len(a)-1)]-a[lo])*(i-lo),3)
def group(intervals):
    d=sum(b-a for a,b in intervals)/1e9
    count=jank=dropped=0; gaps=[]
    for a,b in intervals:
        selected=[f for f in frames if a<=int(f['t'])<b]
        dropped+=sum(f['typ']=='Dropped Frame' for f in selected)
        present={}
        for f in selected:
            if f['typ'] in ('Late Present','Early Present','On-time Present'):
                t=int(f['t']);present[t]=present.get(t,False) or f['j']!='None'
        ts=sorted(present);count+=len(ts);jank+=sum(present.values())
        gaps.extend((y-x)/1e6 for x,y in zip(ts,ts[1:]))
    return dict(frames=count,seconds=round(d,5),fps=round(count/d,2),p50_gap_ms=pct(gaps,.5),p95_gap_ms=pct(gaps,.95),max_gap_ms=max(gaps,default=None),jank_pct=round(100*jank/count,2) if count else None,dropped=dropped)
result={'label':data['label'],'layers':layers,'selected_layer_fragment':layer,'trace_frames_selected':len(frames),'cases':[]}
for c in data['cases']:
    if not c.get('times'):continue
    phases=[(a,b) for k,i,a,b in c['times'] if k=='phase']
    strokes=[(a,b) for k,i,a,b in c['times'] if k=='stroke']
    bounds=query('select start_ts a,end_ts b from trace_bounds')[0]
    covered=all(int(bounds['a'])<=a<b<=int(bounds['b']) for a,b in phases)
    if len(phases)!=3 or len(strokes)!=9:raise RuntimeError('incomplete gesture')
    if not covered:
        if data['label']!='juice-stress':raise RuntimeError('incomplete trace')
        phases=[(a,b) for a,b in phases if int(bounds['a'])<=a<b<=int(bounds['b'])]
        strokes=[(a,b) for a,b in strokes if any(x<=a<b<=y for x,y in phases)]
    pos=[int(row[1].split('|')[1] or 0) for row in c['monitor'] if row[1]]
    result['cases'].append(dict(target=c['target'],grid=c['client'],pane=c['pane_before'],aggregate=group(phases),rounds=[group([p]) for p in phases],moving=group(strokes),position_range=[min(pos),max(pos)],position_unique=len(set(pos)),typed=c.get('typed_marker'),erased=c.get('erase_ok'),screen_changed=c['screen_before']!=c['screen_after'],foreground=c['focus_after']))
Path(args.record).with_suffix('.summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
for c in result['cases']:print(c['target'],c['aggregate']['fps'],c['aggregate']['frames'],c['position_range'])

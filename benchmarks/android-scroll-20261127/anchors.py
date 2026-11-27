"""Measure native TUI transcript movement using only hashed content anchors."""
import collections,hashlib,json,subprocess,sys,time
target=sys.argv[1];duration=float(sys.argv[2]) if len(sys.argv)>2 else 11
def capture():
    lines=subprocess.check_output(['tmux','capture-pane','-pt',target]).decode('utf8','replace').splitlines()
    # Ignore outer chrome, blank lines, separators and short repeated labels.
    indexed={}
    for row,s in enumerate(lines[3:-5],3):
        s=s.strip()
        if len(s)<12 or len(set(s))<6:continue
        h=hashlib.sha256(s.encode()).hexdigest()[:20]
        indexed[h]=None if h in indexed else row
    return {h:r for h,r in indexed.items() if r is not None}
def compare(prev,cur):
    counts=collections.Counter(cur[h]-r for h,r in prev.items() if h in cur)
    ranked=counts.most_common()
    if not ranked:return None,0
    return ranked[0]
start=time.monotonic();prev=capture();first=prev;data=[]
while time.monotonic()-start<duration:
    cur=capture();shift,support=compare(prev,cur)
    data.append([round(time.monotonic()-start,3),len(cur),shift,support,len(set(cur)-set(prev)),len(set(cur)&set(first))])
    prev=cur;time.sleep(.11)
print(json.dumps({'first_count':len(first),'last_count':len(prev),'samples':data}))

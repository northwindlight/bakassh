import sys, time, subprocess, json
target=sys.argv[1]
results=[]
start=time.monotonic()
while time.monotonic()-start<11:
    a=subprocess.run(['tmux','display-message','-p','-t',target,'#{pane_in_mode}|#{scroll_position}|#{pane_width}|#{pane_height}'],capture_output=True,text=True)
    results.append([round(time.monotonic()-start,3),a.stdout.strip()])
    time.sleep(.06)
print(json.dumps(results))

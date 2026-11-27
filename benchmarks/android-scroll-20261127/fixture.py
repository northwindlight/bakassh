"""Deterministic zhanguo-style history, no copied conversation content."""
import hashlib
import os
import sys
import time

def line(i):
    side = '秦楚齐燕韩赵魏'[i % 7]
    text = (f'[{i:05d}·行动] {side} ◇ 售出 木头×{1+i%37:02d} '
            f'（价格 {211+i%190}，回执 {i*13%997:03d}） '
            f'ledger={i*7919%65521:05d} Δ {("▁▂▃▄▅▆▇█"*2)[i%8:i%8+8]} '
            f'│ turn {i//7:04d} │ checkpoint {i%17:02d}')
    return (f'\033[38;5;{[81,114,179,216,141,203,152][i%7]}m'+text+'\033[0m\r\n').encode()

payload=b''.join(line(i) for i in range(2400))+b'ZG-FIXTURE-END 2400\r\n'
if '--hash' in sys.argv:
    print(len(payload),hashlib.sha256(payload).hexdigest())
else:
    import termios
    cfg=termios.tcgetattr(1)
    cfg[1] &= ~termios.OPOST
    termios.tcsetattr(1,termios.TCSANOW,cfg)
    sys.stdout.buffer.write(payload)
    sys.stdout.buffer.flush()
    while True: time.sleep(3600)

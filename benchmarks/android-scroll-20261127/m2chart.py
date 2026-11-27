import time
print('\033[2J\033[H',end='')
for i in range(48):
 print(f'{i+1:02d}  '+['战国行动简报　齐楚秦燕赵魏韩', '粮仓 128 石　售出木材 36 份　回执已登记', '测字：未末　土士　日曰　目自　己已巳', 'RPI5 / tmux    O0  Il1  08:42:16', '边框 ┌───┬───┐　▁▂▃▄▅▆▇█', '终端滚动后，核对上一段正文是否仍可辨认。'][i%6],flush=True)
while True:time.sleep(60)

"""
从 GitHub Actions 的 job 日志里还原界面截图。

CI 的「Print screenshots」步骤会把 app/build/screenshots/*.png 缩小成 JPEG，
用 ===SHOT_BEGIN name=== / ===SHOT_END=== 包住 base64 打印到日志里
（云端开发环境下载不了 Actions 产物，但能读日志）。

用法：python3 tools/decode_ci_screenshots.py <保存下来的 job 日志文件> [输出目录]
"""
import json,re,base64,sys
import os
raw=open(sys.argv[1]).read()
outdir=sys.argv[2] if len(sys.argv)>2 else 'shots'
os.makedirs(outdir, exist_ok=True)
try: text=json.loads(raw).get('logs_content',raw)
except Exception: text=raw
lines=[re.sub(r'^\d{4}-\d\d-\d\dT[\d:.]+Z ?','',l) for l in text.split('\n')]
cur=None; buf=[]
for l in lines:
    m=re.match(r'===SHOT_BEGIN (\S+)===',l)
    if m: cur=m.group(1); buf=[]; continue
    if l.startswith('===SHOT_END===') and cur:
        data=base64.b64decode(''.join(buf).strip()); open(f'{outdir}/{cur}.jpg','wb').write(data); print('wrote',cur,len(data)); cur=None; continue
    if cur: buf.append(l.strip())
for l in lines:
    if re.search(r'ScreenshotTest > |tests completed|FAILED',l): print(l[:240])

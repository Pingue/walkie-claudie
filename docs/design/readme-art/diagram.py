# README architecture diagram (docs/screenshots/diagram-en.png). Design tokens: docs/design/tokens.md
# Build (from this folder): python3 diagram.py en > d.html && google-chrome --headless=new --hide-scrollbars \
#   --force-device-scale-factor=2 --default-background-color=00000000 --window-size=932,330 --screenshot=d.png "file://$PWD/d.html"
#   -> crop the transparent margin (PIL getbbox) into ../../screenshots/diagram-en.png.
import sys
T = {
 'en': dict(phone='Android phone', pc='PC', r1='Talk', r1b='speech recognition', r2='read aloud', ear='Earphones', up='text', down='reply', net='same Wi-Fi · Tailscale', plug='walkie plugin', sess='Claude Code session', n1='only text goes to the PC', n2='no relay server'),
}[sys.argv[1]]
print(f'''<!doctype html><meta charset="utf-8"><style>
@font-face{{font-family:N;src:url(../../../android/app/src/main/assets/fonts/NeoDunggeunmoPro-Regular.ttf)}}
*{{box-sizing:border-box;margin:0}}
html,body{{background:transparent}}
.dev{{width:900px;background:#262626;border-radius:26px;padding:16px 16px 12px;font-family:N;position:relative}}
.led{{position:absolute;top:10px;right:22px;width:7px;height:7px;border-radius:50%;background:#7BD36B}}
.row{{display:flex;align-items:stretch;gap:10px;margin-top:8px}}
.lcd{{background:#9CA88A;border-radius:6px;padding:10px 12px;color:#1F2818;flex:1;
 box-shadow:inset 0 2px 5px rgba(0,0,0,.45);
 background-image:linear-gradient(rgba(31,40,24,.12) 1px,transparent 1px),linear-gradient(90deg,rgba(31,40,24,.12) 1px,transparent 1px);background-size:3px 3px}}
.h{{font-size:12px;opacity:.75;margin-bottom:8px}}
.l{{font-size:15px;line-height:30px;white-space:nowrap}}
.dim{{opacity:.6}}
.mid{{width:170px;display:flex;flex-direction:column;justify-content:center;align-items:center;color:#D6D6D6;gap:6px}}
.ar{{font-size:15px;white-space:nowrap;letter-spacing:1px}}
.net{{font-size:11px;color:#777;margin-top:4px;white-space:nowrap}}
.key{{display:inline-block;background:#3A3A3A;color:#D6D6D6;border-radius:9px;box-shadow:0 3px 0 #111;padding:0 10px;line-height:30px;font-size:15px}}
.foot{{display:flex;justify-content:space-between;color:#777;font-size:11px;margin-top:10px;padding:0 4px}}
</style>
<div class="dev"><div class="led"></div>
<div class="row">
 <div class="lcd"><div class="h">{T['phone']}</div>
  <div class="l" style="margin-top:6px"><span class="key">{T['r1']}</span> → {T['r1b']}</div>
  <div class="l" style="margin-top:14px"><span class="key">{T['ear']}</span> ← {T['r2']}</div></div>
 <div class="mid">
  <div class="ar">{T['up']} ·······▶</div>
  <div class="ar">◀······· {T['down']}</div>
  <div class="net">{T['net']}</div></div>
 <div class="lcd"><div class="h">{T['pc']}</div>
  <div class="l" style="margin-top:6px"><span class="key">{T['plug']}</span></div>
  <div class="l">⇅</div>
  <div class="l"><span class="key">{T['sess']}</span></div></div>
</div>
<div class="foot"><span>· {T['n1']} &nbsp;· {T['n2']}</span><span style="letter-spacing:2px">WALKIE-CLAUDIE</span></div>
</div>''')

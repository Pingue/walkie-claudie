# Walkie-Claudie design tokens — option B, "pager / calculator LCD" (confirmed 2026-10-08)

Mock: `mockB.html` / `mockB.png` (same folder). Principle: **imitate one physical machine (a pager or calculator) all the way** — no gradients, translucency, glowing circles, repeated rounded cards or other "AI defaults".

## Colour (8)
| Token | Value | Use |
|---|---|---|
| `body` | `#262626` | machine body (the whole screen background) |
| `key` | `#3A3A3A` | rubber button face |
| `keyShadow` | `#111111` | shadow under a rubber button (3dp; when pressed 0, and the button moves down 2dp) |
| `keyText` | `#D6D6D6` | button text |
| `lcd` | `#9CA88A` | LCD background |
| `ink` | `#1F2818` | LCD text and lit cells |
| `ghost` | `#1F2818` 12% | unlit cells and the LCD grid (1px lines every 3dp) |
| `ledOk` / `ledRec` | `#7BD36B` / `#C0392B` | small LED at the top right of the body (7dp): connected / transmitting or disconnected (blinks when disconnected) |
| `ledDark` | `#1C1C1C` | small LED at the top right of the [On/Off] button face (6dp): `ledOk` when on, this colour when off. A buzz on switching (one for on, two short for off) plus the power tone. When off, conversation and open-item text drop to 30% and a centred "Off" (tL) shows on the conversation LCD |

- LCD inner shadow: 2dp at the top, 5dp blur, black 45%
- Inverted (transmitting): LCD background `ink`, text `lcd`, no grid

## Type (3 sizes, 2 fonts)
| Token | Size | Use |
|---|---|---|
| `tL` | 24sp | big status ("● TRANSMITTING") |
| `tM` | 15sp | open items, conversation, menu rows |
| `tS` | 12sp | headers (5h, open items 3, connection), helper text |
- Font: **NeoDunggeunmo Pro** (SIL OFL; bundled in the app assets with its licence file). Numbers, keys and addresses use the same font. As in the mock, the conversation is in the pixel font too (if people find it hard to read, switching only the conversation to the system font is the first fix to try)
- Conversation: your lines `ME> ` at 60% strength, Claude's `CLAUDE> ` at 100%. A blinking cursor (8×14dp, 1 s period) at the end of the last line

## Shape and spacing
- Corners: body 26dp (the whole screen, so really the device corner), LCD 6dp, rubber button 9dp — **only these three**
- Spacing: 4 / 8 / 10 / 16dp (16 at the screen edge, 10 between cells, 10·12 inside a cell, 8 between buttons)
- Rubber button height 44dp, at most 3 per row
- The bottom buttons [Settings][On/Off][Talk] use dot-matrix icons instead of text (sliders, power, microphone — a gear looked like a bug, so it was replaced; [Cancel] while transmitting is ✕): about 12 cells wide, strokes 2 cells thick (sliders use a 1-cell line so the knobs show), 2.5dp per cell (2dp for the floating button), 1px gap between dots, sinking 2dp with the button face when pressed, all in `keyText`. On/off is shown by the button's LED. The text stays only as the screen-reader name
- Usage cell: a 10-cell bar (2dp between cells, 7dp high), lit cells `ink`, unlit cells `ghost`
- Antenna: 4 bars (3dp wide, 4/7/10/13dp high) — instead of signal strength it shows "connected = 4 bars, retrying = 1 bar blinking, disconnected = 0 bars"
- `WALKIE-CLAUDIE` engraving at the bottom (11sp, `#777`, letter-spacing 2)

## Screen states
1. Idle: top LCD (channel, antenna, usage) / middle LCD (open items) / bottom LCD (conversation, all the remaining height) / buttons [Settings][Off][Talk]
2. Transmitting: middle and bottom LCDs merged and inverted, "● TRANSMITTING" + "Release to send" ("Press again to send" for a toggle button) + live recognised speech, buttons [Settings][Cancel][Talk], red LED
3. Disconnected: antenna 0 bars, red LED blinking, `-- Disconnected, retrying --` at the bottom of the conversation
4. Settings: one LCD with menu rows (dotted dividers), on/off as `ON` (inverted cell) / `OFF` (outlined cell), button [← Back]
5. Floating button (over other apps): a small walkie — a rubber button inside a `body` housing (9dp corners; button 60×47dp, a dot-matrix microphone instead of text — the same icon as the bottom button at 2dp per cell; the button sinks when pressed; housing padding 14dp at the top and 8dp elsewhere), small LED at the housing's top right (7dp): connected green / transmitting red / disconnected red blinking

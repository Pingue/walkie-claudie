"""Walkie radio sounds, synthesized (no recordings, so nothing to license). Writes 16-bit mono WAVs.

python3 tools/sounds.py android/app/src/main/res/raw
"""
import math, random, struct, sys, wave

R = 22050
random.seed(7)


def tone(f, ms, vol=0.5, f2=None):
    """Sine (or a glide to f2), with 5 ms edges so it doesn't click."""
    n = int(R * ms / 1000); out = []; ph = 0.0
    for i in range(n):
        fr = f if f2 is None else f + (f2 - f) * i / n
        ph += 2 * math.pi * fr / R
        env = min(1, i / (R * 0.005), (n - i) / (R * 0.005))
        out.append(vol * env * math.sin(ph))
    return out


def gap(ms): return [0.0] * int(R * ms / 1000)


def mix(*parts): return [s for p in parts for s in p]


# Patterns from real two-way radios, beeps only (10/9: no static, real radio sounds).
SOUNDS = {
    # pressing talk: MOTOTRBO talk permit tone, 1570/1050/1570/1320 Hz x 40 ms (RadioReference forum, community-measured)
    "talk_start": mix(tone(1570, 40), tone(1050, 40), tone(1570, 40), tone(1320, 40)),
    # message sent: roger beep, high then low
    "sent": mix(tone(1800, 80), tone(1200, 120)),
    # the PC got it (also: earphone button taken back): positive indicator, one short high beep
    "received": tone(1570, 60, 0.35),
    # a reply arrives: call alert, one mid beep before the voice
    "reply": tone(1050, 110, 0.45),
    # a reply from another channel: channel-change double beep, then the voice
    "reply_other": mix(tone(1320, 50), gap(50), tone(1320, 50)),
    # monologue on / off (volume up held): rising / falling three-tone, like a radio's mode-change prompt
    "mono_start": mix(tone(880, 60), tone(1320, 60), tone(1760, 90)),
    "mono_end": mix(tone(1760, 60), tone(1320, 60), tone(880, 90)),
    # walkie switched on / off: a long rising chirp / a falling sweep, like a radio powering up / down
    "power_on": mix(tone(700, 70), gap(30), tone(1400, 160, f2=1700)),
    "power_off": tone(1300, 280, f2=500),
    # nothing heard / cancelled: talk prohibit, one low steady tone
    "empty": tone(440, 300),
    # couldn't send for a while: out-of-range bonk-bonk
    "failed": mix(tone(350, 200), gap(100), tone(350, 200)),
}

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    for name, s in SOUNDS.items():
        with wave.open(f"{out}/{name}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(R)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32000)) for x in s))

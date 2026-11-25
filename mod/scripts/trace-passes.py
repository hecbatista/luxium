#!/usr/bin/env python3
"""GPU time per pass from a Metal System Trace (run with LUXIUM_GPU_LABELS=1).
Export the two tables first:
  xcrun xctrace export --input t.trace --xpath '/trace-toc/run[@number="1"]/data/table[@schema="metal-gpu-intervals"]' > gpu.xml
  xcrun xctrace export --input t.trace --xpath '/trace-toc/run[@number="1"]/data/table[@schema="metal-application-intervals"]' > app.xml
then: python3 -I mod/scripts/trace-passes.py gpu.xml app.xml [top]
GPU rows are joined to pass labels on encoder-id."""
import sys, re, collections
import xml.etree.ElementTree as ET

def rows(path):
    ids, cols = {}, []
    for ev, el in ET.iterparse(path, events=("end",)):
        if el.tag == "mnemonic":
            cols.append(el.text); continue
        if el.tag != "row":
            continue
        rec = {}
        for name, k in zip(cols, list(el)):
            if k.get("ref") is not None:
                rec[name] = ids.get(k.get("ref"))
            else:
                v = (k.get("fmt"), k.text)
                for sub in k.iter():
                    if sub.get("id") is not None:
                        ids[sub.get("id")] = (sub.get("fmt"), sub.text) if sub is not k else v
                rec[name] = v
        yield rec
        el.clear()

gpu, app = sys.argv[1], sys.argv[2]
top = int(sys.argv[3]) if len(sys.argv) > 3 else 30
label = {}
for r in rows(app):
    u = r.get("encoder-id"); l = r.get("event-label")
    if u and l and l[0]:
        m = re.search(r" - :(.*?) \((?:Main Thread|Render thread|[^()]*Thread)", l[0])
        lab = m.group(1) if m else l[0]
        label[u[1]] = re.sub(r"Render Type ", "", lab)
per = collections.defaultdict(lambda: [0, 0])
frames = set(); unl = 0
for r in rows(gpu):
    p = r.get("process")
    if not p or "java" not in (p[0] or ""):
        continue
    u = r.get("encoder-id"); d = int((r.get("duration") or (0, "0"))[1] or 0)
    ch = (r.get("channel-name") or ("?",))[0]
    fr = r.get("frame-number")
    if fr and fr[1]: frames.add(fr[1])
    lab = label.get(u[1]) if u else None
    if lab is None:
        unl += d; lab = "(unlabelled)"
    per[(lab, ch)][0] += d; per[(lab, ch)][1] += 1
nf = max(1, len(frames))
byl = collections.defaultdict(lambda: collections.Counter())
for (lab, ch), (d, n) in per.items():
    byl[lab][ch] += d
tot = sum(sum(c.values()) for c in byl.values())
print(f"{nf} frames; GPU busy (all channels) {tot/1e6/nf:.2f} ms/frame; unlabelled {unl/1e6/nf:.2f}")
for lab, c in sorted(byl.items(), key=lambda kv: -sum(kv[1].values()))[:top]:
    s = sum(c.values())
    print(f"{s/1e6/nf:6.3f} ms  (v {c['Vertex']/1e6/nf:.3f} f {c['Fragment']/1e6/nf:.3f} c {c['Compute']/1e6/nf:.3f})  {lab[:90]}")

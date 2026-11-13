import sys, json, re, collections
m = json.loads(sys.stdin.readline())["message"]
ths = [t.strip() for t in m.split("\n\n")[0:]]
c = collections.Counter()
shown = False
for t in ths:
    i = t.find("Thread[")
    if i < 0:
        continue
    t = t[i:]
    h = t.split("\n")[0]
    n = re.match(r"Thread\[#\d+,([^,]*),", h).group(1)
    kind = "env-ec" if "env-ec" in n else "remoteclock" if "remoteclock" in n else "wallclock" if "wallclock" in n else n
    c[(kind, re.search(r"state=(\w+)", h).group(1))] += 1
    if not shown and n.endswith("sv1-remoteclock-0"):
        shown = True
        keep = [l.strip() for l in t.split("\n") if re.search(r"Thread\[|Await\$\.ready|noisyAwaitResult|time\.RemoteClock|time\.Clock", l)]
        print("\n".join(x[:150] for x in keep[:8]))
for k, v in sorted(c.items()):
    print(v, k[0], k[1])

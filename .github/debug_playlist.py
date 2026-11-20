import json, urllib.request

URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
CTX = {"client": {"clientName": "WEB_REMIX", "clientVersion": "1.20251111.01.00", "hl": "en", "gl": "US"}}

def browse(bid):
    req = urllib.request.Request(URL, data=json.dumps({"context": CTX, "browseId": bid}).encode(),
        headers={"Content-Type": "application/json", "Origin": "https://music.youtube.com"})
    return json.load(urllib.request.urlopen(req, timeout=30))

def find_ids(o, out):
    if isinstance(o, dict):
        b = o.get("browseId")
        if isinstance(b, str) and b.startswith("VL"): out.append(b)
        for v in o.values(): find_ids(v, out)
    elif isinstance(o, list):
        for v in o: find_ids(v, out)

def paths(o, p="", depth=0, maxd=9):
    if depth > maxd: return
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("trackingParams", "clickTrackingParams", "accessibility", "accessibilityData"): continue
            print(f"{p}.{k}" + (f"[{len(v)}]" if isinstance(v, list) else ""))
            paths(v, f"{p}.{k}", depth + 1, maxd)
    elif isinstance(o, list) and o:
        paths(o[0], p + "[0]", depth + 1, maxd)

home = browse("FEmusic_home")
ids = []
find_ids(home, ids)
print("candidate playlists:", ids[:10])
for bid in ids[:2]:
    r = browse(bid)
    print("\n==========", bid, "top keys:", list(r.keys()))
    c = r.get("contents", {})
    print("contents keys:", list(c.keys()))
    tc = c.get("twoColumnBrowseResultsRenderer")
    if tc:
        sec = tc.get("secondaryContents", {}).get("sectionListRenderer", {}).get("contents", [])
        print("secondary section count:", len(sec), [list(x.keys()) for x in sec])
        if sec:
            shelf = sec[0].get("musicPlaylistShelfRenderer") or sec[0].get("musicShelfRenderer") or {}
            items = shelf.get("contents", [])
            print("shelf items:", len(items), [list(i.keys()) for i in items[:2]])
        tabs = tc.get("tabs", [])
        base = tabs[0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"] if tabs else []
        print("tab section keys:", [list(x.keys()) for x in base])
    paths(c, "contents", maxd=7)

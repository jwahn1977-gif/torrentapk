"""Print a magnet URI (no trackers -> DHT/public-tracker test) for a .torrent file."""
import hashlib
import sys
import urllib.parse


def decode(b, i=0):
    c = b[i:i + 1]
    if c == b"i":
        j = b.index(b"e", i)
        return int(b[i + 1:j]), j + 1
    if c == b"l":
        i += 1
        out = []
        while b[i:i + 1] != b"e":
            v, i = decode(b, i)
            out.append(v)
        return out, i + 1
    if c == b"d":
        i += 1
        out = {}
        while b[i:i + 1] != b"e":
            k, i = decode(b, i)
            v, i = decode(b, i)
            out[k] = v
        return out, i + 1
    j = b.index(b":", i)
    n = int(b[i:j])
    return b[j + 1:j + 1 + n], j + 1 + n


def info_span(b):
    """Return raw bencoded bytes of the 'info' dict."""
    d, _ = decode(b)
    key = b"4:info"
    start = b.index(key) + len(key)
    _, end = decode(b, start)
    return b[start:end], d


data = open(sys.argv[1], "rb").read()
raw, d = info_span(data)
h = hashlib.sha1(raw).hexdigest()
name = d[b"info"][b"name"].decode()
mode = sys.argv[2] if len(sys.argv) > 2 else "notrackers"
uri = f"magnet:?xt=urn:btih:{h}&dn={urllib.parse.quote(name)}"
if mode == "trackers":
    uri += "&tr=" + urllib.parse.quote(d[b"announce"].decode(), safe="")
print(uri)

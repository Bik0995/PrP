"""Construit app/src/main/assets/sites.bin à partir de la liste « porn » de The Block List Project
(github.com/blocklistproject/Lists, domaine public / Unlicense ; en-tête du fichier : MIT).

Format : gzip d'une suite d'entiers 64 bits signés gros-boutistes, triés : le hachage FNV-1a 64
de chaque nom de domaine (ASCII, minuscules). Les sous-domaines dont un parent figure déjà dans
la liste sont retirés (PrP vérifie aussi chaque domaine parent). Usage :
    python3 liste_sites.py porn-nl.txt
"""
import gzip, os, struct, sys

def fnv1a64(s: str) -> int:
    h = 0xcbf29ce484222325
    for b in s.encode("ascii", "ignore"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= 1 << 63 else h   # comme un Long de Kotlin

src = sys.argv[1]
dom = set()
for l in open(src, encoding="utf-8", errors="ignore"):
    l = l.strip().lower()
    if not l or l.startswith("#"): continue
    d = l.split()[-1].strip(".")
    if "." in d: dom.add(d)

def a_parent(d):
    p = d
    while "." in p:
        p = p.split(".", 1)[1]
        if "." in p and p in dom: return True
    return False

garde = sorted({fnv1a64(d) for d in dom if not a_parent(d)})
dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "sites.bin")
with gzip.open(dst, "wb", 9) as f:
    f.write(struct.pack(">%dq" % len(garde), *garde))
print(len(dom), "domaines lus,", len(garde), "gardés ->", dst)

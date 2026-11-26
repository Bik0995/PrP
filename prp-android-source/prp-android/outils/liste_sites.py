"""Construit app/src/main/assets/sites.bin, la liste de sites pour adultes de PrP.

Sources (à télécharger avant) :
  --blp  liste « porn » de The Block List Project (github.com/blocklistproject/Lists,
         alt-version/porn-nl.txt ; Unlicense / domaine public).
  --ut1  catégorie « adult » de la liste de l'Université Toulouse Capitole (UT1),
         dsi.ut-capitole.fr/blacklists (copie : github.com/olbat/ut1-blacklists ; CC BY-SA 4.0).
  --psl  liste des suffixes publics (publicsuffix.org) : on ne bloque jamais un suffixe entier.

Règles (« ne bloquer que ce qui est visé ») :
  * The Block List Project : pris tel quel, sauf exclusions.
  * UT1 : très complète dans toutes les langues, mais elle contient aussi des sites
    piratés ou mal classés (universités, banques, boutiques). Un domaine qui n'est que
    dans UT1 n'est gardé que si son NOM contient aussi un indice adulte (liste INDICES,
    en plusieurs langues) : deux signaux indépendants.
  * Exclus partout : suffixes publics, sites d'État, d'université et d'école, et les
    grandes plateformes mixtes (EXEMPTES), où PrP floute les images au lieu de bloquer.
  * Les copies nationales de Blogspot (x.blogspot.ru, x.blogspot.com.eg…) sont ramenées
    à x.blogspot.com, comme le fait Filtre.kt à la lecture.

Format : gzip d'entiers 64 bits signés gros-boutistes, triés : hachage FNV-1a 64 de chaque
domaine (ASCII, minuscules). Les sous-domaines dont un parent est gardé sont retirés.

Usage : python3 liste_sites.py --blp porn-nl.txt --ut1 domains --psl public_suffix_list.dat
"""
import argparse, gzip, os, re, struct

def fnv1a64(s: str) -> int:
    h = 0xcbf29ce484222325
    for b in s.encode("ascii", "ignore"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= 1 << 63 else h   # comme un Long de Kotlin

BLOGSPOT = re.compile(r"^(.*\.)?blogspot\.[a-z.]+$")

def normaliser(d: str) -> str:
    d = d.strip().lower().strip(".")
    m = BLOGSPOT.match(d)
    if m: d = (m.group(1) or "") + "blogspot.com"
    for p in ("www.", "m.", "mobile."):
        if d.startswith(p) and d.count(".") >= 2: d = d[len(p):]
    return d

# Indices de nom adulte, dans plusieurs langues (transcriptions latines surtout : les noms
# de domaine sont presque toujours en ASCII). Ils ne suffisent PAS à bloquer : ils
# confirment seulement un domaine déjà classé adulte par UT1.
INDICES = re.compile("|".join([
    # anglais et international
    r"porn", r"sex", r"xxx", r"xx", r"hentai", r"nud", r"naked", r"nsfw", r"erot", r"milf",
    r"bdsm", r"fuck", r"boob", r"tits", r"titty", r"pussy", r"cock", r"dick", r"anal", r"ass",
    r"cum", r"slut", r"whore", r"hooker", r"escort", r"fetish", r"kink", r"fap", r"jizz",
    r"tube", r"cam", r"girl", r"babe", r"chick", r"teen", r"mature", r"granny", r"mom",
    r"gay", r"lesb", r"shemale", r"tranny", r"trans", r"swing", r"strip", r"hot", r"horny",
    r"lust", r"busty", r"bust", r"blow", r"orgy", r"orgas", r"18", r"69", r"vid", r"clip",
    r"hub", r"onlyfans", r"leak", r"fans", r"cams", r"chat", r"date", r"dating", r"adult",
    r"xham", r"xnxx", r"xvid", r"redtube", r"spank", r"brazz", r"chaturb", r"bonga",
    # japonais, chinois, coréen
    r"jav", r"ero", r"ecchi", r"oppai", r"jukujo", r"nuki", r"doujin", r"hanime", r"av",
    r"seqing", r"chengren", r"huang", r"91", r"caoliu", r"madou", r"yase", r"yadong",
    # russe, ukrainien
    r"seks", r"trah", r"ebl", r"eba", r"pizd", r"huy", r"hui", r"siski", r"shlyuh", r"prostit",
    r"intim", r"dosug", r"golye", r"golaya", r"porev", r"sosal", r"minet", r"zrel", r"droch",
    r"konch", r"porno",
    # arabe, persan, turc
    r"sks", r"neek", r"nik", r"sharmo", r"sharmu", r"aflam", r"arab", r"shahvan", r"kos",
    r"sikis", r"sikiş", r"turbanl",
    # espagnol, portugais, italien, français, allemand, polonais
    r"puta", r"zorra", r"culo", r"tetas", r"follar", r"coger", r"buceta", r"novinh", r"putar",
    r"safad", r"gostos", r"sesso", r"troi", r"figa", r"salope", r"baise", r"cul", r"coquin",
    r"libertin", r"beurette", r"nue", r"fick", r"titten", r"nackt", r"geil", r"muschi",
    r"dupc", r"rucha", r"cipk",
    # indonésien, hindi, vietnamien, thaï
    r"bokep", r"ngentot", r"memek", r"colmek", r"toket", r"sange", r"chudai", r"desi",
    r"bhabhi", r"kand", r"masala", r"mms", r"phim", r"heo",
]))

# Grandes plateformes au contenu mixte : jamais bloquées en entier (la détection d'images
# floute ce qui doit l'être). Leurs sous-domaines propres restent dans la liste s'ils y sont.
EXEMPTES = {
    "pixiv.net", "dmm.com", "nicovideo.jp", "xiaohongshu.com", "fanbox.cc", "toomics.com",
    "character.ai", "perchance.org", "seaart.ai", "deviantart.com", "furaffinity.net",
    "reddit.com", "tumblr.com", "blogspot.com", "x.com", "twitter.com", "imgur.com",
    "nyaa.si", "badoo.com", "ome.tv", "cosmopolitan.com", "yourtango.com", "lacuarta.com",
    "super.cz", "videa.hu", "vijaykarnataka.com", "survey.alchemer.com", "pocketoption.com",
    "ambitionbox.com", "hmv.co.jp", "southparkstudios.com", "funnyjunk.com", "my-cima.net",
    "sotwe.com", "suruga-ya.jp", "tubidy.cool", "in.net", "adultchildren.org",
    "caixapopular.es", "talkceltic.net", "breakingnews.ie", "ticinonews.ch", "lankasri.com",
    "hot-news.gr", "perezhilton.com", "feber.se", "humancondition.com", "biomed.fi",
    "kaiptc.org", "vardhaman.org", "imagetwist.com", "uploadhouse.com", "listcrawler.eu",
    "apkmid.com", "evergreen-fishing.com", "edition-aurum.de", "petcornerdubai.com",
    "samashti.in", "cnam.md", "greyhound.ie", "porcelanite.com.mx", "panaxis.com.my",
    "kartikasari.com", "jackpotcity.co.za", "esa.co.za", "goldengate.hu", "telegram.org",
    "t.me", "vk.com", "ok.ru", "mail.ru", "yandex.ru", "google.com", "youtube.com",
    "wikipedia.org", "archive.org", "mega.nz", "dropbox.com", "fc2.com",
    "enfemenino.com", "wonderwall.com", "demonia.com", "cduniverse.com", "hipforums.com",
    "aidungeon.com", "influx.com",
}

INSTITUTION = re.compile(
    r"(^|\.)(gov|edu|mil|int)(\.[a-z]{2})?$|(^|\.)(ac|gob|gouv|go|govt|gv|sch|k12|nic|police|mil|edu)\.[a-z]{2}$"
)
INSTITUTION_MOTS = re.compile(r"univ|school|college|academy|campus|elearning|lms|portal|bank")

def charger(chemin):
    s = set()
    with open(chemin, encoding="utf-8", errors="ignore") as f:
        for l in f:
            l = l.strip()
            if not l or l.startswith("#"): continue
            d = normaliser(l.split()[-1])
            if "." in d and " " not in d: s.add(d)
    return s

def charger_psl(chemin):
    s = set()
    with open(chemin, encoding="utf-8") as f:
        for l in f:
            l = l.strip()
            if not l or l.startswith("//"): continue
            s.add(l.lstrip("*.!").lower())
    return s

def main():
    a = argparse.ArgumentParser()
    a.add_argument("--blp", required=True)
    a.add_argument("--ut1")
    a.add_argument("--psl")
    a.add_argument("--sortie", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                     "..", "app", "src", "main", "assets", "sites.bin"))
    o = a.parse_args()
    psl = charger_psl(o.psl) if o.psl else set()
    fort = re.compile(r"porn|sex|xxx|hentai|xvideo|xnxx|xhamster")

    def exclu(d):
        if d in psl or d in EXEMPTES: return True
        if any(d.endswith("." + e) and e in ("google.com", "youtube.com", "wikipedia.org") for e in EXEMPTES): return True
        if INSTITUTION.search(d): return True          # État, université, école : jamais
        if INSTITUTION_MOTS.search(d.rsplit(".", 1)[0]) and not fort.search(d): return True
        return False

    blp = charger(o.blp)
    garde = {d for d in blp if not exclu(d)}
    n_ut1 = n_ut1_garde = 0
    if o.ut1:
        ut1 = charger(o.ut1)
        n_ut1 = len(ut1)
        for d in ut1:
            if d in garde or exclu(d): continue
            nom = d.rsplit(".", 1)[0]               # sans l'extension finale
            if INDICES.search(nom):
                garde.add(d); n_ut1_garde += 1

    def a_parent(d):
        p = d
        while "." in p:
            p = p.split(".", 1)[1]
            if "." in p and p in garde: return True
        return False

    final = sorted({fnv1a64(d) for d in garde if not a_parent(d)})
    with gzip.open(o.sortie, "wb", 9) as f:
        f.write(struct.pack(">%dq" % len(final), *final))
    print(f"Block List Project : {len(blp)} ; UT1 : {n_ut1} lus, {n_ut1_garde} ajoutés ; "
          f"{len(final)} domaines dans {o.sortie}")

if __name__ == "__main__":
    main()

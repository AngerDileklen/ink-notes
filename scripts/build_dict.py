#!/usr/bin/env python3
"""Builds app/src/main/assets/dict.db, the offline English + Turkish dictionary, from three free data sets:

  * Princeton WordNet 3.0 (WNdb-3.0.tar.gz, unpacked to <src>/dict): English meanings, word types, examples.
    WordNet licence: https://wordnet.princeton.edu/license-and-commercial-use
  * WikDict en-tr.sqlite3 and tr-en.sqlite3 (https://www.wikdict.com, built from Wiktionary, CC BY-SA 3.0):
    English -> Turkish and Turkish -> English translations.

Usage: python3 scripts/build_dict.py "/path/to/dict-src"      (the folder holding dict/, en-tr.sqlite3, tr-en.sqlite3)
The source files are not kept in git; only this script and the small result are.
"""
import os, re, sqlite3, sys

src = sys.argv[1] if len(sys.argv) > 1 else "."
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "dict.db")
os.makedirs(os.path.dirname(out), exist_ok=True)
if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.executescript("""
PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
CREATE TABLE syn(id INTEGER PRIMARY KEY, pos TEXT, gloss TEXT, ex TEXT);
CREATE TABLE lem(word TEXT, rank INTEGER, syn INTEGER, pf INTEGER);
CREATE TABLE en_tr(word TEXT PRIMARY KEY, simple TEXT, senses TEXT) WITHOUT ROWID;
CREATE TABLE tr_en(word TEXT PRIMARY KEY, simple TEXT, senses TEXT) WITHOUT ROWID;
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
""")

# ---- WordNet ---------------------------------------------------------------------------------------------------
POS = {"noun": "n", "verb": "v", "adj": "a", "adv": "r"}
syn_ids = {}
rows = []
for name, tag in POS.items():
    with open(os.path.join(src, "dict", "data." + name), encoding="utf-8") as f:
        for line in f:
            if line.startswith("  "):
                continue
            head, _, gloss = line.partition("|")
            parts = head.split()
            offset = parts[0]
            sid = len(syn_ids) + 1
            syn_ids[(tag, offset)] = sid
            gloss = gloss.strip()
            segs = [s.strip() for s in gloss.split(";")]
            definition = [s for s in segs if s and not s.startswith('"')]
            example = next((s.strip('"') for s in segs if s.startswith('"')), "")
            rows.append((sid, tag, "; ".join(definition), example))
db.executemany("INSERT INTO syn VALUES (?,?,?,?)", rows)
print("synsets", len(rows))

lemrows = []
for name, tag in POS.items():
    with open(os.path.join(src, "dict", "index." + name), encoding="utf-8") as f:
        for line in f:
            if line.startswith("  "):
                continue
            p = line.split()
            lemma = p[0].replace("_", " ")
            synset_cnt = int(p[2]); ptr_cnt = int(p[3])
            tagged = int(p[5 + ptr_cnt])                    # how often this word is used with this word type in a sample text
            offsets = p[6 + ptr_cnt: 6 + ptr_cnt + synset_cnt]
            for rank, off in enumerate(offsets):
                sid = syn_ids.get((tag, off))
                if sid:
                    lemrows.append((lemma.lower(), rank, sid, tagged))
db.executemany("INSERT INTO lem VALUES (?,?,?,?)", lemrows)
db.execute("CREATE INDEX lem_word ON lem(word)")
print("lemma links", len(lemrows))

# ---- WikDict ---------------------------------------------------------------------------------------------------
def lower_tr(s):
    return s.replace("I", "ı").replace("İ", "i").lower()

def load(path, table, lower):
    c = sqlite3.connect(path)
    simple = {}
    for w, t, score in c.execute("SELECT written_rep, trans_list, max_score FROM simple_translation ORDER BY max_score DESC"):
        k = lower(w.strip())
        if not k or not t:
            continue
        simple.setdefault(k, t)
    senses = {}
    for w, sense, t in c.execute("SELECT written_rep, sense_list, trans_list FROM translation_grouped ORDER BY score DESC"):
        k = lower(w.strip())
        if not k or not t:
            continue
        line = ((sense + " => ") if sense else "") + t
        lst = senses.setdefault(k, [])
        if line not in lst and len(lst) < 6:
            lst.append(line)
    data = []
    for k, t in simple.items():
        data.append((k, t, "\n".join(senses.get(k, []))))
    db.executemany(f"INSERT OR IGNORE INTO {table} VALUES (?,?,?)", data)
    print(table, len(data))

load(os.path.join(src, "en-tr.sqlite3"), "en_tr", str.lower)
load(os.path.join(src, "tr-en.sqlite3"), "tr_en", lower_tr)

db.execute("INSERT INTO meta VALUES ('version', '1')")
db.execute("INSERT INTO meta VALUES ('credits', 'English meanings: Princeton WordNet 3.0. Translations: WikDict (Wiktionary, CC BY-SA 3.0).')")
db.commit()
db.execute("VACUUM")
db.close()
print("wrote", os.path.abspath(out), os.path.getsize(out) // 1024, "KB")

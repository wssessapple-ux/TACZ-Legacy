# -*- coding: utf-8 -*-
import json, os, re, collections, sys

root = r"E:\vip\TACZ-Legacy\src\main\resources\assets\tacz\custom\tacz_default_gun\data\tacz\recipes"
refs = collections.defaultdict(set)

def walk_item(node, fname):
    if isinstance(node, str):
        refs["item:" + node].add(fname)
    elif isinstance(node, dict):
        for k, v in node.items():
            if k == "tag" and isinstance(v, str):
                refs["tag:" + v].add(fname)
            elif k == "item":
                walk_item(v, fname)
    elif isinstance(node, list):
        for c in node:
            walk_item(c, fname)

total_files = 0
for dirpath, _, files in os.walk(root):
    for fn in files:
        if not fn.endswith(".json"):
            continue
        p = os.path.join(dirpath, fn)
        try:
            with open(p, encoding="utf-8") as f:
                j = json.load(f)
        except Exception:
            continue
        total_files += 1
        mats = j.get("materials")
        if not mats:
            continue
        for m in mats:
            it = m.get("item") if isinstance(m, dict) else None
            if it is None:
                continue
            walk_item(it, fn)

hi = ['netherite','copper','amethyst','crying_obsidian','deepslate','raw_',
      'ancient_debris','sculk','mangrove','cherry','bamboo','moss','warden',
      'storage_blocks','prismarine','shulker','lodestone','respawn_anchor',
      'calcite','tuff','amethyst','rose_quartz','echo','resin','pale_oak','azure']
hi_re = re.compile("|".join(hi))
print("=== 全量唯一引用（递归，含数组/嵌套）===")
hicount = 0
for k in sorted(refs):
    flag = "  <== 高版本独有" if hi_re.search(k) else ""
    if hi_re.search(k):
        hicount += 1
    print("%s  (%s)%s" % (k, ",".join(sorted(refs[k])), flag))
print("\n=== 共 %d 种唯一引用；疑似高版本独有 %d 种；配方文件 %d 个 ===" % (len(refs), hicount, total_files))

# Localization Guide

TACZ-Legacy pulls every player-facing string from language files. Adding or finishing a language **does not require any code change** — it is one plain text file, submitted through a Pull Request.

- **Mod:** unofficial 1.12.2 Forge port of TaCZ
- **License:** GPL-3.0
- **Language files:** `src/main/resources/assets/tacz/lang/`

## How it works

The game loads `lang/<locale>.lang` based on the player's selected game language. A line looks like this:

```
tooltip.tacz.chunk_loader.desc=Keeps surrounding chunks loaded so remote devices and farms keep running
```

- The part **before** `=` is the translation key — **never change it**.
- The part **after** `=` is the translated text — this is what you edit.
- On 1.12.2 only the **`.lang`** files are loaded. The `.json` files in the same folder are kept for the next-gen port and are **not read by this version** — do not edit them for 1.12.2.

## Rules

1. **Keep keys untouched.** Copy the key exactly, including dots and capitalization.
2. **Keep formatting codes.** Leave `§` color codes (e.g. `§a`, `§7`, `§c`) exactly where they are.
3. **Keep placeholders.** Leave `%s`, `%d` (and their order) unchanged; they are filled in by the game.
4. **Match the English file 1:1.** Do not add or remove lines. If a key has no translation yet, copy it from `en_us.lang` first, then translate the value.
5. **Do not translate proper names.** Mod names, item IDs, gun names that are already brand names, and command names stay as-is.
6. **Encoding:** files are UTF-8, one `key=value` per line, `#` starts a comment.

## Contributing

### Finish an existing language

1. Fork the repository.
2. Open your language file in `src/main/resources/assets/tacz/lang/`.
3. Compare it against `en_us.lang` (the source of truth, 331 keys):
   - Fill in any missing keys.
   - Replace values that are still English with your translation.
   - Fix empty values.
4. Open a Pull Request. In the description, say which language you worked on.

### Add a new language

1. Fork the repository.
2. Copy `en_us.lang` to `<locale>.lang` using the Minecraft locale code (see table below).
3. Translate every value after `=`.
4. Open a Pull Request.

### Locale codes

| Code | Language | Code | Language |
|---|---|---|---|
| `en_us` | English (US) | `ko_kr` | Korean |
| `zh_cn` | Chinese (Simplified) | `pl_pl` | Polish |
| `zh_tw` | Chinese (Traditional) | `pt_br` | Portuguese (Brazil) |
| `ca_es` | Catalan | `ru_ru` | Russian |
| `da_dk` | Danish | `th_th` | Thai |
| `de_de` | German | `tr_tr` | Turkish |
| `es_es` | Spanish | `uk_ua` | Ukrainian |
| `eu_es` | Basque | `vi_vn` | Vietnamese |
| `fr_fr` | French | `id_id` | Indonesian |
| `it_it` | Italian | `ja_jp` | Japanese |

Other standard Minecraft locale codes are welcome as well.

## Current status

Baseline: `en_us.lang` has **331 keys**. The table below reflects the last audit. "English copy" means the value is still identical to the English baseline and still needs translating.

| Locale | File | Keys | Missing keys | Empty values | Still English | Notes |
|---|---|---:|---:|---:|---:|---|
| zh_cn | `zh_cn.lang` | 331 | 0 | 0 | 2 | Reference, complete |
| ja_jp | `ja_jp.lang` | 278 | 53 | 0 | 8 | Mostly translated |
| ko_kr | `ko_kr.lang` | 278 | 53 | 0 | 7 | Mostly translated |
| ru_ru | `ru_ru.lang` | 278 | 53 | 0 | 6 | Mostly translated |
| pl_pl | `pl_pl.lang` | 278 | 53 | 0 | 8 | Mostly translated |
| vi_vn | `vi_vn.lang` | 278 | 53 | 0 | 8 | Mostly translated |
| it_it | `it_it.lang` | 278 | 53 | 0 | 10 | Mostly translated |
| id_id | `id_id.lang` | 278 | 53 | 0 | 17 | Partial |
| de_de | `de_de.lang` | 278 | 53 | 0 | 16 | Partial |
| fr_fr | `fr_fr.lang` | 278 | 53 | 0 | 18 | Partial |
| es_es | `es_es.lang` | 278 | 53 | 1 | 45 | Partial |
| eu_es | `eu_es.lang` | 278 | 53 | 0 | 45 | Partial |
| ca_es | `ca_es.lang` | 278 | 53 | 0 | 37 | Partial |
| uk_ua | `uk_ua.lang` | 278 | 53 | 0 | 38 | Partial |
| pt_br | `pt_br.lang` | 278 | 53 | 3 | 24 | Partial, has empty values |
| th_th | `th_th.lang` | 278 | 53 | 1 | 8 | Partial |
| tr_tr | `tr_tr.lang` | 289 | 53 | 0 | 4 | Partial, has extra legacy keys |
| zh_tw | `zh_tw.lang` | 278 | 53 | 0 | 2 | Partial |
| da_dk | `da_dk.lang` | 278 | 53 | 1 | 192 | Mostly unfinished, English placeholders |

### What the 53 missing keys are

The legacy locale files predate the newest content. The missing keys fall into these groups:

- **Drone (suicide drone):** civilian / military names, tooltips, lock / cancel / invalid / wrong-dimension messages.
- **Chunk Loader:** block name, GUI labels (range, status, owner, switch), tooltips.
- **Creative tab groups:** guns, ammo, attachments and their categories.
- **Gunsmith table:** filter, missing ingredient, no matching recipe, pack URL, refit buttons, scale controls.
- **Misc client/render strings:** translucent toggle, overheat HUD label, a few tooltip labels.

When finishing a file, copy these keys from `en_us.lang` and translate them.

## Credit

Contributors are credited in the README and release notes. If you translate a language, include your GitHub username in the Pull Request description so it can be listed.

## Questions

Open an issue on the repository or leave a comment on CurseForge.

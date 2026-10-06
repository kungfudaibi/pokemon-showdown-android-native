"""Build compact, offline English/Simplified Chinese Pokédex name tables.

Source: https://github.com/PokeAPI/pokeapi/tree/master/data/v2/csv
The generated TSV is bundled in the APK, so battle controls work offline from
the translation service and retain protocol English names for commands.
"""

import csv
import io
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
OUT = Path(__file__).resolve().parents[1] / "assets" / "dex_names.tsv"


def rows(file_name):
    with urllib.request.urlopen(BASE + file_name, timeout=30) as response:
        return list(csv.DictReader(io.TextIOWrapper(response, encoding="utf-8")))


def names(file_name, key):
    result = {}
    for row in rows(file_name):
        lang = row["local_language_id"]
        if lang in ("9", "12"):
            result.setdefault(row[key], {})[lang] = row["name"]
    return result


def main():
    output = []
    type_names = {row["id"]: row["identifier"].title() for row in rows("types.csv")}
    move_data = {row["id"]: row for row in rows("moves.csv")}
    for kind, file_name, key in (
        ("M", "move_names.csv", "move_id"),
        ("P", "pokemon_species_names.csv", "pokemon_species_id"),
    ):
        entries = names(file_name, key)
        for number in sorted(entries, key=int):
            pair = entries[number]
            if "9" in pair and "12" in pair:
                line = f"{kind}\t{pair['9']}\t{pair['12']}"
                if kind == "M":
                    move = move_data.get(number, {})
                    line += f"\t{type_names.get(move.get('type_id'), 'Normal')}"
                    line += f"\t{move.get('damage_class_id', '1')}\t{move.get('power', '') or '0'}"
                output.append(line)
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text("\n".join(output) + "\n", encoding="utf-8")
    print(f"Wrote {len(output)} names to {OUT}")


if __name__ == "__main__":
    main()

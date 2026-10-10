"""Package supplied trained artifacts; never creates dummy weights or downloads models.

python scripts/make_model_bundle.py --spec scripts/model-pack.example.json \
    --artifacts PATH --output PATH.zip
The Laya label order must come from its trained export, not taxonomy iteration order.
"""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


def child(root, name):
    path = (root / name).resolve()
    if not name or Path(name).is_absolute() or root not in path.parents:
        raise ValueError(f"Artifact path escapes bundle root: {name}")
    if not path.is_file():
        raise ValueError(f"Missing trained artifact: {path}")
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.artifacts.resolve()
    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    taxonomy = json.loads((Path(__file__).resolve().parents[1] / "app/src/main/assets/incident_taxonomy.json").read_text(encoding="utf-8"))
    expected_labels = {item["id"] for item in taxonomy["incident_types"]} | {"none", "undetermined"}
    if set(spec["models"]["laya"]["labels"]) != expected_labels:
        raise ValueError("Set Laya labels to the actual exported index order: all 28 taxonomy IDs + none + undetermined.")
    names = set()
    for role in ("asr", "llm", "cfc", "laya"):
        model = spec["models"][role]
        if not model["version"] or "replace-with" in model["version"]:
            raise ValueError(f"Set a real, versioned artifact identifier for {role}.")
        names.add(model["file"])
        if role in ("cfc", "laya"):
            if model["export_contract"] != "word_ids_mask_v1":
                raise ValueError("Export a matching wrapper or implement the trained tokenizer adapter first.")
            names.add(model["vocabulary"])
            vocabulary = json.loads(child(root, model["vocabulary"]).read_text(encoding="utf-8"))
            if not vocabulary or any(not isinstance(key, str) or not isinstance(value, int) or value < 0 for key, value in vocabulary.items()):
                raise ValueError("Vocabulary must map normalized tokens to non-negative integer IDs.")
    hashes = {}
    for name in sorted(names):
        digest = hashlib.sha256()
        with child(root, name).open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        hashes[name] = digest.hexdigest()
    spec["sha256"] = hashes
    args.output.parent.mkdir(parents=True, exist_ok=True)
    staging = args.output.with_suffix(args.output.suffix + ".part")
    try:
        with zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as bundle:
            bundle.writestr("manifest.json", json.dumps(spec, indent=2))
            for name in sorted(names):
                bundle.write(child(root, name), name)
        staging.replace(args.output)
    finally:
        staging.unlink(missing_ok=True)
    print(f"Packaged {len(names)} supplied artifacts: {args.output}")


if __name__ == "__main__":
    main()

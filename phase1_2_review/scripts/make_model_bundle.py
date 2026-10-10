"""Package supplied trained artifacts; never creates dummy weights or downloads models.

python scripts/make_model_bundle.py --spec scripts/model-pack.example.json \
    --artifacts PATH --output PATH.zip
The Laya label order must come from its trained export, not taxonomy iteration order.
"""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def child(root, name):
    path = (root / name).resolve()
    if not name or Path(name).is_absolute() or root not in path.parents:
        raise ValueError(f"Artifact path escapes bundle root: {name}")
    if not path.is_file():
        raise ValueError(f"Missing trained artifact: {path}")
    return path


def validate_spec(spec, root):
    if spec.get("contract_version") != 1:
        raise ValueError("Unsupported contract version.")
    mode = spec.get("pipeline", "full")
    roles = {"asr", "llm"} if mode == "asr_llm" else {"asr", "llm", "cfc", "laya"}
    if mode not in {"asr_llm", "full"} or set(spec.get("models", {})) != roles:
        raise ValueError("Model roles must match the explicitly selected pipeline.")
    if mode == "full":
        taxonomy = json.loads((Path(__file__).resolve().parents[1] / "app/src/main/assets/incident_taxonomy.json").read_text(encoding="utf-8"))
        expected_labels = {item["id"] for item in taxonomy["incident_types"]} | {"none", "undetermined"}
        if set(spec["models"]["laya"]["labels"]) != expected_labels:
            raise ValueError("Set Laya labels to the actual exported index order: all 28 taxonomy IDs + none + undetermined.")
    names = set()
    for role in sorted(roles):
        model = spec["models"][role]
        if not model["version"] or "replace-with" in model["version"]:
            raise ValueError(f"Set a real, versioned artifact identifier for {role}.")
        names.add(model["file"])
        family, backend = {"asr": ("Whisper", "whisper.cpp-cpu"), "llm": ("Qwen3", "llama.cpp-cpu"),
                           "cfc": ("CfC", "onnx-cpu"), "laya": ("Laya", "onnx-cpu")}[role]
        if model.get("family") != family or model.get("backend") != backend:
            raise ValueError(f"Unsupported family or backend for {role}.")
        artifact = child(root, model["file"])
        if not artifact.stat().st_size:
            raise ValueError(f"Empty artifact: {artifact}")
        if role in ("asr", "llm"):
            with artifact.open("rb") as source:
                magic = source.read(4)
            if magic != (b"lmgg" if role == "asr" else b"GGUF"):
                raise ValueError(f"Invalid {role} artifact header.")
        if role == "asr" and model.get("multilingual") is not True:
            raise ValueError("Whisper must be multilingual.")
        if role in ("cfc", "laya"):
            if model.get("quantization") != "int8" or not 8 <= model.get("max_tokens", 0) <= 2048:
                raise ValueError("CfC/Laya require INT8 and max_tokens between 8 and 2048.")
            if model["export_contract"] != "word_ids_mask_v1":
                raise ValueError("Export a matching wrapper or implement the trained tokenizer adapter first.")
            names.add(model["vocabulary"])
            vocabulary = json.loads(child(root, model["vocabulary"]).read_text(encoding="utf-8"))
            if not vocabulary or any(not isinstance(key, str) or not isinstance(value, int) or value < 0 for key, value in vocabulary.items()):
                raise ValueError("Vocabulary must map normalized tokens to non-negative integer IDs.")
    return names


def package_bundle(spec, root, output):
    root = Path(root).resolve()
    output = Path(output)
    spec = json.loads(json.dumps(spec))
    names = validate_spec(spec, root)
    hashes = {}
    for name in sorted(names):
        digest = hashlib.sha256()
        with child(root, name).open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        hashes[name] = digest.hexdigest()
    spec["sha256"] = hashes
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_suffix(output.suffix + ".part")
    try:
        with zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as bundle:
            bundle.writestr("manifest.json", json.dumps(spec, indent=2))
            for name in sorted(names):
                bundle.write(child(root, name), name)
        staging.replace(output)
    finally:
        staging.unlink(missing_ok=True)
    print(f"Packaged {len(names)} supplied artifacts: {output}")
    return spec


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package_bundle(json.loads(args.spec.read_text(encoding="utf-8")), args.artifacts, args.output)


if __name__ == "__main__":
    main()

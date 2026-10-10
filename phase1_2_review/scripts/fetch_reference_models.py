"""Download pinned real Whisper/Qwen artifacts and prepare the ASR+LLM bundle.

Runs on the workstation only; the Android app never downloads weights.
Uses only the Python standard library. Interrupted downloads are resumable.
"""
import argparse
import hashlib
import json
import time
import urllib.request
import shutil
import re
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

MODELS = {
    "asr": {
        "repo": "ggerganov/whisper.cpp",
        "revision": "5359861c739e955e79d9a303bcbc70fb988958b1",
        "file": "ggml-base.bin",
        "sha256": "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        "size": 147951465,
        "family": "Whisper", "backend": "whisper.cpp-cpu", "multilingual": True,
    },
    "llm": {
        "repo": "Qwen/Qwen3-0.6B-GGUF",
        "revision": "23749fefcc72300e3a2ad315e1317431b06b590a",
        "file": "Qwen3-0.6B-Q8_0.gguf",
        "sha256": "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
        "size": 639446688,
        "family": "Qwen3", "backend": "llama.cpp-cpu",
    },
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parallel_tail(partial, model, url, workers):
    """Resume the existing prefix and fetch independent tail ranges concurrently."""
    prefix = partial.stat().st_size if partial.exists() else 0
    if prefix >= model["size"]:
        return
    # Preserve disjoint fragments even if the connection count changes on resume.
    saved = []
    for part in partial.parent.glob(partial.name + ".range-*"):
        bounds = re.search(r"\.range-(\d+)-(\d+)$", part.name)
        if not bounds:
            continue
        start, end = map(int, bounds.groups())
        received = part.stat().st_size
        if start < prefix or end >= model["size"] or received > end - start + 1:
            raise ValueError("Invalid cached model range.")
        if received:
            saved.append((start, start + received - 1, part))
    saved.sort()
    cursor = prefix
    gaps = []
    for start, end, _ in saved:
        if start < cursor:
            raise ValueError("Overlapping cached model ranges.")
        if start > cursor:
            gaps.append((cursor, start - 1))
        cursor = end + 1
    if cursor < model["size"]:
        gaps.append((cursor, model["size"] - 1))
    missing = sum(end - start + 1 for start, end in gaps)
    block = max(1, (missing + workers - 1) // workers)
    ranges = [(start, min(start + block - 1, end))
              for gap_start, end in gaps for start in range(gap_start, end + 1, block)]

    def fetch(bounds):
        start, end = bounds
        part = partial.with_name(partial.name + f".range-{start}-{end}")
        for attempt in range(5):
            received = part.stat().st_size if part.exists() else 0
            expected = end - start + 1
            if received == expected:
                return part
            if received > expected:
                raise ValueError("Oversized range fragment.")
            request = urllib.request.Request(url + f"&range={start + received}-{end}",
                headers={"Range": f"bytes={start + received}-{end}", "User-Agent": "IncidentAssistant/1.0"})
            try:
                with urllib.request.urlopen(request, timeout=90) as response:
                    if response.status != 206 or response.headers.get("Content-Range") != f"bytes {start + received}-{end}/{model['size']}":
                        raise ValueError("Model host did not honor the requested byte range.")
                    with part.open("ab") as output:
                        while chunk := response.read(1024 * 1024):
                            received += len(chunk)
                            if received > expected:
                                raise ValueError("Range fragment exceeded its bounds.")
                            output.write(chunk)
                if received != expected:
                    raise IOError("Range fragment ended early.")
                print(f"Completed model range {start}-{end}", flush=True)
                return part
            except (OSError, TimeoutError) as error:
                if attempt == 4:
                    raise
                print(f"Retrying model range: {error}", flush=True)
                time.sleep(2)
        raise IOError("Could not download model range.")

    print(f"Downloading {model['file']} with {workers} connections; resuming {prefix} bytes", flush=True)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        fetched = list(pool.map(fetch, ranges))
    fragments = [(start, part) for (start, _), part in zip(ranges, fetched)]
    fragments += [(start, part) for start, _, part in saved]
    fragments.sort()
    joining = partial.with_name(partial.name + ".joining")
    with joining.open("wb") as output:
        if partial.exists():
            with partial.open("rb") as source:
                shutil.copyfileobj(source, output, 1024 * 1024)
        for _, part in fragments:
            with part.open("rb") as source:
                shutil.copyfileobj(source, output, 1024 * 1024)
    joining.replace(partial)
    for _, part in fragments:
        part.unlink()


def download(root, model, workers=4):
    target = root / model["file"]
    if target.is_file():
        if target.stat().st_size == model["size"] and sha256(target) == model["sha256"]:
            print(f"Verified cached {target.name}", flush=True)
            return
        raise ValueError(f"Existing artifact does not match pinned checksum: {target}")
    partial = target.with_suffix(target.suffix + ".part")
    url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{model['file']}?download=true"
    if workers > 1:
        parallel_tail(partial, model, url, workers)
    for attempt in range(5):
        offset = partial.stat().st_size if partial.exists() else 0
        if offset == model["size"]:
            break
        if offset > model["size"]:
            raise ValueError(f"Oversized partial download: {partial}")
        headers = {"User-Agent": "IncidentAssistant-model-preparation/1.0"}
        if offset:
            headers["Range"] = f"bytes={offset}-"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=90) as response:
                if offset and response.status == 206:
                    if not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                        raise ValueError("Server returned an inconsistent byte range.")
                    mode = "ab"
                else:
                    offset = 0
                    mode = "wb"
                print(f"Downloading {target.name} ({model['size'] // (1024 * 1024)} MiB), starting at {offset}", flush=True)
                announced = offset
                with partial.open(mode) as output:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        offset += len(chunk)
                        if offset > model["size"]:
                            raise ValueError("Download exceeded expected artifact size.")
                        output.write(chunk)
                        if offset - announced >= 32 * 1024 * 1024:
                            print(f"  {target.name}: {offset * 100 / model['size']:.0f}%", flush=True)
                            announced = offset
                if offset != model["size"]:
                    raise IOError("Download ended before the complete model arrived.")
            break
        except (OSError, TimeoutError) as error:
            if attempt == 4:
                raise
            print(f"Retrying interrupted download: {error}", flush=True)
            time.sleep(2)
    if partial.stat().st_size != model["size"] or sha256(partial) != model["sha256"]:
        raise ValueError(f"Downloaded artifact checksum mismatch: {partial}")
    partial.replace(target)
    print(f"Verified {target.name}: {model['sha256']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("tmp/reference-models"))
    parser.add_argument("--download-only", action="store_true")
    parser.add_argument("--workers", type=int, choices=range(1, 9), default=4)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    specs = {}
    for role, model in MODELS.items():
        download(args.output_dir, model, args.workers)
        specs[role] = {key: value for key, value in model.items() if key not in {"size", "sha256", "repo", "revision"}}
        specs[role]["version"] = f"{model['repo']}@{model['revision']}:{model['file']}"
    spec = {"contract_version": 1, "pipeline": "asr_llm", "models": specs,
            "sources": {role: {"repository": model["repo"], "revision": model["revision"],
                               "sha256": model["sha256"]} for role, model in MODELS.items()}}
    path = args.output_dir / "asr-llm.spec.json"
    path.write_text(json.dumps(spec, indent=2), encoding="utf-8")
    if not args.download_only:
        from make_model_bundle import package_bundle
        output = args.output_dir / "incident-asr-llm.zip"
        package_bundle(spec, args.output_dir.resolve(), output)
        print(f"Import this bundle in the Android app: {output.resolve()}", flush=True)


if __name__ == "__main__":
    main()

"""Audit supplied M1 labelled JSONL; returns failure for split overlap or schema errors.

Each row: {id, split: train|test, turns: [{text, incident_type,
sentences: [{text, relevant: boolean}], fields: [{field_name, evidence}]}]}.
Incident type may be an ID, none, undetermined, or a list of IDs for two incidents.
"""
import argparse
import collections
import json
import re
from pathlib import Path


def normalized(text):
    return re.sub(r"\s+", " ", text.strip().lower())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--require-m1-counts", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("tmp/phase1/dataset-audit.json"))
    args = parser.parse_args()
    taxonomy = json.loads((Path(__file__).resolve().parents[1] / "app/src/main/assets/incident_taxonomy.json").read_text(encoding="utf-8"))
    valid = {item["id"] for item in taxonomy["incident_types"]} | {"none", "undetermined"}
    sentences = {"train": set(), "test": set()}
    conversations = collections.Counter()
    labels = {"train": collections.Counter(), "test": collections.Counter()}
    ids = set()
    failures = []
    sentence_count = turn_count = field_count = 0
    for line_number, line in enumerate(args.dataset.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            identifier = row["id"]
            if not identifier or identifier in ids:
                raise ValueError("duplicate/missing conversation ID")
            ids.add(identifier)
            split = row["split"]
            if split not in sentences:
                raise ValueError("split must be train or test")
            conversations[split] += 1
            if not row["turns"]:
                raise ValueError("empty conversation")
            for turn in row["turns"]:
                text = turn["text"]
                if not isinstance(text, str) or not text.strip():
                    raise ValueError("empty/non-string turn")
                turn_count += 1
                targets = turn["incident_type"]
                targets = targets if isinstance(targets, list) else [targets]
                if not targets or any(target not in valid for target in targets):
                    raise ValueError("unknown incident label")
                labels[split].update(targets)
                if not turn["sentences"]:
                    raise ValueError("missing sentence labels")
                for sentence in turn["sentences"]:
                    if type(sentence["relevant"]) is not bool or not sentence["text"].strip() or sentence["text"] not in text:
                        raise ValueError("invalid sentence/evidence relevance annotation")
                    sentences[split].add(normalized(sentence["text"]))
                    sentence_count += 1
                for field in turn["fields"]:
                    if not field["field_name"] or not field["evidence"] or field["evidence"] not in text:
                        raise ValueError("ungrounded field annotation")
                    field_count += 1
        except (KeyError, TypeError, ValueError) as error:
            failures.append(f"line {line_number}: {error}")
    overlap = sentences["train"] & sentences["test"]
    if overlap:
        failures.append(f"{len(overlap)} normalized test sentences also appear in training")
    missing = {split: sorted(valid - set(counter)) for split, counter in labels.items()}
    if any(missing.values()):
        failures.append("one or more splits do not cover all 28 types + none/undetermined")
    if args.require_m1_counts and (conversations["train"] != 1164 or conversations["test"] != 128):
        failures.append("conversation counts differ from M1's 1164 train / 128 test")
    report = {"conversation_counts": dict(conversations), "turn_count": turn_count,
        "sentence_count": sentence_count, "field_mentions": field_count,
        "labels": {split: dict(counter) for split, counter in labels.items()},
        "missing_labels": missing, "overlap_count": len(overlap), "failures": failures,
        "passed_structural_audit": not failures,
        "pending_audits": ["filler-word/type shortcuts", "turn-count/length/location/style shortcuts",
            "look-alike coverage", "independent human recordings", "pre/post-INT8 accuracy"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"counts": dict(conversations), "passed": not failures, "failures": failures}))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Choose the embedding backend's τ on the §5 pairs (`extraction_eval_design.md` §6).

§6: take the §5 accept/reject pairs as a labelled dev set, pick the τ that best
separates accept from reject, freeze it, and never adjust it against
precision/recall output. "Best separates" is read the way the lexical τ was
checked — through the eval's own one-to-one matching:

1. Score every gold x extracted pair of `threshold_pairs.MEETINGS` (29 pairs,
   7 the same task) with `embedding_similarity`, and print them.
2. Find every τ, in steps of 0.001, that passes `matching_failures`: matching
   returns exactly the hand-labelled pairs, also with each true extracted item
   left out. The chosen τ is the middle of that range, rounded to two
   decimals: the most margin on both sides.
3. Recheck the chosen τ.

Changed 2026-10-28, before any real output was scored with embeddings: the
first version of this script chose τ pair by pair (the most pairs on the right
side). On the §5 pairs that gave 0.83, which drops three true pairs scoring
0.751-0.774 and fails 8 of the 9 matching checks; two wrong pairs score 0.789
and 0.793, above them, so no τ separates the pairs one by one. The matching
keeps those wrong pairs out, which is what the lexical τ relied on too.

It prints and changes nothing: the number goes into
`similarity.EMBEDDING_THRESHOLD` by hand, with a dated note in §6.

Needs the embedding model (`models/bge-small-en-v1.5/`, see models/README.md,
with MODEL_CACHE_DIR pointing at `models/`); no LLM, no network.

Usage:
    python scripts/derive_embedding_threshold.py
"""

import sys
from functools import cache

from mind.evaluation.similarity import embedding_similarity
from mind.evaluation.threshold_pairs import MEETINGS, matching_failures

# Each pair is scored many times while scanning τ; the model call is the cost.
similarity = cache(embedding_similarity)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    rows = [
        (meeting, g, e, similarity(gold_text, extracted_text), (g, e) in same)
        for meeting, (gold, extracted, same) in MEETINGS.items()
        for g, gold_text in gold.items()
        for e, extracted_text in extracted.items()
    ]
    print("=== 1. every §5 pair, highest first ===")
    for meeting, g, e, score, same in sorted(rows, key=lambda row: -row[3]):
        print(f"{score:.3f}  {'SAME' if same else '    '}  {meeting} {g}-{e}")
    positives = [score for *_, score, same in rows if same]
    negatives = [score for *_, score, same in rows if not same]
    print(
        f"same task: {len(positives)} pairs, {min(positives):.3f}-{max(positives):.3f}; "
        f"different: {len(negatives)} pairs, {min(negatives):.3f}-{max(negatives):.3f}"
    )

    passing = [t / 1000 for t in range(1001) if not matching_failures(similarity, t / 1000)]
    print("\n=== 2. tau values that pass every matching check ===")
    if not passing:
        print("none: no tau passes; report it, do not pick one")
        return 1
    contiguous = len(passing) == round((passing[-1] - passing[0]) * 1000) + 1
    print(f"{passing[0]:.3f}-{passing[-1]:.3f}{'' if contiguous else ' (with gaps)'}")
    tau = round((passing[0] + passing[-1]) / 2, 2)

    failures = matching_failures(similarity, tau)
    print(f"\n=== 3. chosen tau = {tau:.2f} (the middle of the range) ===")
    print("all checks pass" if not failures else "\n".join(["FAILS:", *failures]))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())

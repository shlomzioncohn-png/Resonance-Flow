"""Position-aligned OCR scoring: match words by bounding-box overlap, then CER.

Why this exists: a plain WER/CER compares two serialized strings, so it
depends on reading order -- and `tesseract --psm 11` has none. The
earlier `char_similarity_score` dodged the order problem with a `max`
over every OCR word, which has no zero point and never penalizes extra
output (200 random gibberish words scored ~42% against FUNSD's ground
truth, 1000 scored ~48%: more output, higher score).

This scores by *position* instead, in the style of the ICDAR end-to-end
protocol: pair each ground-truth word box with at most one OCR word box
(IoU >= threshold, one-to-one, highest IoU first), compare the text
inside each pair, and count what's left over:

- unmatched GT word  -> deletion (all of its characters)
- unmatched OCR word -> insertion (all of its characters)

    CER = (substitutions + deletions + insertions) / ground-truth characters

Lower is better, 0 is perfect, and it can exceed 1 when the OCR emits
more garbage than there is ground truth -- that is the penalty the old
metric lacked.

Matching threshold (DEFAULT_IOU_THRESHOLD = 0.3, not ICDAR's usual 0.5):
FUNSD's word boxes and Tesseract's follow different conventions -- centers
line up (median offset ~0.5px) but heights differ (OCR boxes up to ~2x
taller on some pages). Measured on the words Tesseract read *exactly*
(a known-true pairing): IoU >= 0.5 recovers only 52% of them, IoU >= 0.3
recovers 93%, and neither lets a wrong-text word into any GT word's
candidate set (looser rules -- overlap coefficient, center-in-box --
recover 100% but admit 0.27-0.42 wrong-text candidates per GT word). The
threshold was picked for matching quality on those known pairs, not for
how good the resulting CER looked; the CER at other thresholds is
reported in ocr-asr-benchmark.md. Caveat: "known-true pairs" are by
construction the correctly-read words, so recovery for misread words
can't be measured this way.

Two variants, both position-based:

- strict (`aligned_score`): one-to-one word pairing, described above.
- grouped (`grouped_score`): many-to-one / one-to-many, in the spirit of
  CLEval's instance matching. GT and OCR words that overlap are clustered
  into groups (connected components of the overlap graph); each group is
  scored as one string -- the GT words' text vs. the OCR words' text, both
  concatenated in position order -- so a merged or split word is *not*
  charged as an error when its characters are right. Unmatched GT words are
  still deletions and unmatched OCR words still insertions.

Why grouped is the one to use for decisions (chosen before looking at its
numbers): CER is meant to measure *characters*, and strict matching mixes
in tokenization -- a merged `43215` + `9428` -> `432159428` has zero wrong
characters yet costs a deletion plus an insertion. Strict stays as the
conservative bound. What grouped deliberately ignores is word boundaries;
it still penalizes wrong, missing, and extra characters.

Known limitation of one-to-one matching: when OCR merges two GT words
into one box (or splits one), the merged/split box overlaps each GT word
by less than the threshold, so it scores as a deletion plus an insertion
even if the characters are right. `unmatched_gt` / `unmatched_ocr` are
reported so that effect is visible instead of hidden inside the CER.
"""

from __future__ import annotations

import re

from pydantic import BaseModel

DEFAULT_IOU_THRESHOLD = 0.3


def normalize(word: str) -> str:
    return re.sub(r"[^\w]", "", word).lower()


class WordBox(BaseModel):
    """One word and its axis-aligned bounding box (pixels, y grows down)."""

    text: str
    x0: float
    y0: float
    x1: float
    y1: float


class AlignedScore(BaseModel):
    """Result of aligned_score(); every count is on normalized, non-empty words."""

    gt_words: int
    ocr_words: int
    matched: int
    exact: int  # matched pairs whose normalized text is identical
    deletions: int  # unmatched GT words
    insertions: int  # unmatched OCR words
    char_edits: int  # substitutions + deletions + insertions, in characters
    gt_chars: int
    cer: float
    word_precision: float  # exact / ocr_words
    word_recall: float  # exact / gt_words


def iou(a: WordBox, b: WordBox) -> float:
    iw = min(a.x1, b.x1) - max(a.x0, b.x0)
    ih = min(a.y1, b.y1) - max(a.y0, b.y0)
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
    return inter / union if union > 0 else 0.0


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def match_boxes(gt: list[WordBox], ocr: list[WordBox], iou_threshold: float) -> list[tuple[int, int]]:
    """One-to-one (gt_index, ocr_index) pairs, highest IoU first, ties by index."""
    candidates = []
    for i, g in enumerate(gt):
        for j, o in enumerate(ocr):
            overlap = iou(g, o)
            if overlap >= iou_threshold:
                candidates.append((-overlap, i, j))
    candidates.sort()
    used_gt: set[int] = set()
    used_ocr: set[int] = set()
    pairs = []
    for _, i, j in candidates:
        if i in used_gt or j in used_ocr:
            continue
        used_gt.add(i)
        used_ocr.add(j)
        pairs.append((i, j))
    return pairs


def _prepare(words: list[WordBox]) -> list[WordBox]:
    prepared = []
    for w in words:
        text = normalize(w.text)
        if text:
            prepared.append(WordBox(text=text, x0=w.x0, y0=w.y0, x1=w.x1, y1=w.y1))
    return prepared


def aligned_score(
    gt_words: list[WordBox],
    ocr_words: list[WordBox],
    iou_threshold: float = DEFAULT_IOU_THRESHOLD,
) -> AlignedScore:
    gt = _prepare(gt_words)
    ocr = _prepare(ocr_words)
    gt_chars = sum(len(g.text) for g in gt)
    if gt_chars == 0:
        raise ValueError("ground truth has no characters -- CER is undefined")

    pairs = match_boxes(gt, ocr, iou_threshold)
    matched_gt = {i for i, _ in pairs}
    matched_ocr = {j for _, j in pairs}

    pair_edits = sum(levenshtein(gt[i].text, ocr[j].text) for i, j in pairs)
    deleted = [g for i, g in enumerate(gt) if i not in matched_gt]
    inserted = [o for j, o in enumerate(ocr) if j not in matched_ocr]
    char_edits = pair_edits + sum(len(g.text) for g in deleted) + sum(len(o.text) for o in inserted)
    exact = sum(1 for i, j in pairs if gt[i].text == ocr[j].text)

    return AlignedScore(
        gt_words=len(gt),
        ocr_words=len(ocr),
        matched=len(pairs),
        exact=exact,
        deletions=len(deleted),
        insertions=len(inserted),
        char_edits=char_edits,
        gt_chars=gt_chars,
        cer=char_edits / gt_chars,
        word_precision=exact / len(ocr) if ocr else 0.0,
        word_recall=exact / len(gt),
    )


DEFAULT_OVERLAP_THRESHOLD = 0.5
LINE_TOLERANCE = 0.6  # x the smaller median box height; see _reading_order


class GroupedScore(BaseModel):
    """Result of grouped_score(); counts are on normalized, non-empty words."""

    gt_words: int
    ocr_words: int
    groups: int  # clusters containing at least one GT word and one OCR word
    one_to_one_groups: int
    merged_or_split_groups: int  # more than one word on at least one side
    largest_group_words: int  # GT + OCR words in the biggest cluster
    unmatched_gt: int  # GT words no OCR word overlaps (deletions)
    unmatched_ocr: int  # OCR words no GT word overlaps (insertions)
    group_edits: int  # character edits inside groups
    deleted_chars: int
    inserted_chars: int
    char_edits: int
    gt_chars: int
    cer: float


def overlap_coefficient(a: WordBox, b: WordBox) -> float:
    """Intersection area over the *smaller* box's area: 1.0 when one box sits
    inside the other, which is how a merged word's box relates to the words
    it swallowed (and a tall OCR box to a short GT box)."""
    iw = min(a.x1, b.x1) - max(a.x0, b.x0)
    ih = min(a.y1, b.y1) - max(a.y0, b.y0)
    if iw <= 0 or ih <= 0:
        return 0.0
    smaller = min((a.x1 - a.x0) * (a.y1 - a.y0), (b.x1 - b.x0) * (b.y1 - b.y0))
    return iw * ih / smaller if smaller > 0 else 0.0


def _median_height(words: list[WordBox]) -> float:
    heights = sorted(w.y1 - w.y0 for w in words)
    return max(heights[len(heights) // 2], 1.0)


def _reading_order(words: list[WordBox], tolerance: float) -> list[WordBox]:
    """Top-to-bottom by line, left-to-right within a line. Words share a line
    when their vertical centers are within `tolerance` of the line's mean
    center, so a group on one text line is simply sorted by x.

    The tolerance is chosen once per group and used for *both* the GT and the
    OCR side (see grouped_score): box centers agree between FUNSD and
    Tesseract but heights don't (OCR boxes up to ~2x taller), and a
    per-side tolerance made the taller OCR boxes merge neighbouring lines --
    producing a different word order than the GT for the same text."""
    if len(words) < 2:
        return list(words)
    by_center = sorted(words, key=lambda w: (w.y0 + w.y1) / 2)
    lines: list[list[WordBox]] = [[by_center[0]]]
    for w in by_center[1:]:
        line_center = sum((x.y0 + x.y1) / 2 for x in lines[-1]) / len(lines[-1])
        if abs((w.y0 + w.y1) / 2 - line_center) <= tolerance:
            lines[-1].append(w)
        else:
            lines.append([w])
    return [w for line in lines for w in sorted(line, key=lambda w: w.x0)]


def _cluster(gt: list[WordBox], ocr: list[WordBox], threshold: float) -> list[list[int]]:
    """Connected components of the GT/OCR overlap graph. Nodes 0..G-1 are GT
    words, G..G+O-1 are OCR words; returns each component's node list."""
    parent = list(range(len(gt) + len(ocr)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for i, g in enumerate(gt):
        for j, o in enumerate(ocr):
            if overlap_coefficient(g, o) >= threshold:
                parent[find(i)] = find(len(gt) + j)
    components: dict[int, list[int]] = {}
    for node in range(len(parent)):
        components.setdefault(find(node), []).append(node)
    return list(components.values())


def grouped_score(
    gt_words: list[WordBox],
    ocr_words: list[WordBox],
    overlap_threshold: float = DEFAULT_OVERLAP_THRESHOLD,
) -> GroupedScore:
    gt = _prepare(gt_words)
    ocr = _prepare(ocr_words)
    gt_chars = sum(len(g.text) for g in gt)
    if gt_chars == 0:
        raise ValueError("ground truth has no characters -- CER is undefined")

    group_edits = deleted_chars = inserted_chars = 0
    groups = one_to_one = merged_or_split = unmatched_gt = unmatched_ocr = largest = 0
    for component in _cluster(gt, ocr, overlap_threshold):
        gt_side = [gt[n] for n in component if n < len(gt)]
        ocr_side = [ocr[n - len(gt)] for n in component if n >= len(gt)]
        if not ocr_side:
            unmatched_gt += len(gt_side)
            deleted_chars += sum(len(g.text) for g in gt_side)
        elif not gt_side:
            unmatched_ocr += len(ocr_side)
            inserted_chars += sum(len(o.text) for o in ocr_side)
        else:
            groups += 1
            if len(gt_side) == 1 and len(ocr_side) == 1:
                one_to_one += 1
            else:
                merged_or_split += 1
            largest = max(largest, len(component))
            tolerance = LINE_TOLERANCE * min(_median_height(gt_side), _median_height(ocr_side))
            group_edits += levenshtein(
                "".join(w.text for w in _reading_order(gt_side, tolerance)),
                "".join(w.text for w in _reading_order(ocr_side, tolerance)),
            )

    char_edits = group_edits + deleted_chars + inserted_chars
    return GroupedScore(
        gt_words=len(gt),
        ocr_words=len(ocr),
        groups=groups,
        one_to_one_groups=one_to_one,
        merged_or_split_groups=merged_or_split,
        largest_group_words=largest,
        unmatched_gt=unmatched_gt,
        unmatched_ocr=unmatched_ocr,
        group_edits=group_edits,
        deleted_chars=deleted_chars,
        inserted_chars=inserted_chars,
        char_edits=char_edits,
        gt_chars=gt_chars,
        cer=char_edits / gt_chars,
    )

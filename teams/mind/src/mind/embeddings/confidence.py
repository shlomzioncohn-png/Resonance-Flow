"""#476: map a raw similarity score to a confidence, relative to the
decision threshold that turned it into a match -- not the raw similarity
itself. See embeddings/README.md, "Confidence" for the reasoning and the
team-wide scale this matches.
"""


def confidence_from_similarity(similarity: float, threshold: float) -> float:
    """A hit right at `threshold` is the most marginal call that still
    counts as a match at all -- confidence 0.0, not whatever (often
    still high-looking) value its raw similarity happens to be. A
    perfect match (similarity=1.0) is confidence 1.0. Linear in between:
    twice the distance above threshold is twice the confidence.

    Clamped to [0, 1] defensively -- every caller already filters on
    `similarity >= threshold` before calling this, so in practice this
    never actually clamps.
    """
    span = 1.0 - threshold
    if span <= 0:
        return 1.0 if similarity >= threshold else 0.0
    return max(0.0, min(1.0, (similarity - threshold) / span))

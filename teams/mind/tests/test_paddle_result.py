"""Tests for paddle_result_lines() in benchmarks/ocr_asr_benchmark.py.

PaddleOCR's page result is a dict-like object; a renamed key used to read as an
empty OCR output (`res.get("rec_texts", [])`) and produce a silently wrong score.
These pin that it fails loudly instead. No paddleocr needed: the function only
reads keys, so plain dicts stand in for the result.
"""

import pytest
from benchmarks.ocr_asr_benchmark import paddle_result_lines


class FakeBoxes:
    """Stands in for the numpy array PaddleOCR returns (only .tolist() is used)."""

    def __init__(self, rows):
        self._rows = rows

    def tolist(self):
        return self._rows


def test_reads_one_box_per_text_line():
    res = {"rec_texts": ["ATT.GEN", "Dec 1098"], "rec_boxes": FakeBoxes([[100, 82, 352, 100], [417, 81, 536, 100]])}
    lines = paddle_result_lines(res)
    assert [line.text for line in lines] == ["ATT.GEN", "Dec 1098"]
    assert (lines[0].x0, lines[0].y0, lines[0].x1, lines[0].y1) == (100, 82, 352, 100)


def test_accepts_plain_lists_of_boxes_too():
    assert len(paddle_result_lines({"rec_texts": ["a"], "rec_boxes": [[0, 0, 10, 10]]})) == 1


def test_a_page_with_no_text_is_fine_not_an_error():
    # PaddleOCR returns both keys with empty values for a blank page
    assert paddle_result_lines({"rec_texts": [], "rec_boxes": FakeBoxes([])}) == []


def test_a_missing_rec_texts_key_raises_instead_of_reading_as_empty():
    with pytest.raises(RuntimeError, match="rec_texts"):
        paddle_result_lines({"rec_boxes": FakeBoxes([[0, 0, 1, 1]]), "other_key": 1})


def test_a_missing_rec_boxes_key_raises_and_names_the_keys_it_did_find():
    with pytest.raises(RuntimeError, match="rec_boxes.*other_key"):
        paddle_result_lines({"rec_texts": ["a"], "other_key": 1})


def test_texts_and_boxes_of_different_lengths_raise():
    with pytest.raises(ValueError):
        paddle_result_lines({"rec_texts": ["a", "b"], "rec_boxes": FakeBoxes([[0, 0, 1, 1]])})

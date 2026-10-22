"""
Tests for how a name found in text is matched against a name in Inbox (#131).

The table of cases is the one reviewed with the mentor, one row per case, by
category instead of by number: the numbers are starting points, the categories
are what #131 promises (and what #132 reads its rules from).

  EXACT         every word of the name is there, same words: 1.0
  PARTIAL       the mention is only part of the name ("Dana" for "Dana Cohen")
  TYPO_FULL     every word of the name is there, one with a typo: below 1.0
  TYPO_PARTIAL  part of the name, with a typo
  NONE          a word of the mention matches no word of the name: 0.0
"""

import pytest

from mind.config import InboxDirectorySettings
from mind.integrations.name_matching import (
    PARTIAL_SCORE,
    WORD_MATCH_MIN,
    score_names,
    words,
)

DANA = ["Dana Cohen", "Dana Cohen"]  # display name, first + last name
DANA_BY_USERNAME = ["DC", "dana.cohen"]  # a display name that says nothing, a username that does


def _check(category, score):
    if category == "EXACT":
        assert score == 1.0
    elif category == "PARTIAL":
        assert score == pytest.approx(PARTIAL_SCORE)
    elif category == "TYPO_FULL":
        assert PARTIAL_SCORE < score < 1.0
    elif category == "TYPO_PARTIAL":
        assert WORD_MATCH_MIN * PARTIAL_SCORE <= score < PARTIAL_SCORE
    elif category == "NONE":
        assert score == 0.0
    else:
        raise AssertionError(f"unknown category {category}")


CASES = [
    pytest.param("Dana Cohen", DANA, "EXACT", id="1-full-name"),
    pytest.param("dana  cohen", DANA, "EXACT", id="2-case-and-spacing"),
    pytest.param("Cohen Dana", DANA, "EXACT", id="3-word-order-does-not-matter"),
    pytest.param("Dana Cohen", DANA_BY_USERNAME, "EXACT", id="4-full-name-by-username"),
    pytest.param("dana.cohen", DANA_BY_USERNAME, "EXACT", id="5-username-as-mentioned"),
    pytest.param("Dana", DANA, "PARTIAL", id="6-first-name-only"),
    pytest.param("Cohen", DANA, "PARTIAL", id="7-last-name-only"),
    pytest.param("Dana", ["Danny Levi"], "NONE", id="9-a-different-first-name"),
    pytest.param("Dena Cohen", DANA, "TYPO_FULL", id="10-typo-with-last-name"),
    pytest.param("Dena", DANA, "TYPO_PARTIAL", id="11-typo-in-a-lone-first-name"),
    pytest.param("Yossi", DANA, "NONE", id="12-nobody-by-that-name"),
    pytest.param("Dana from finance", DANA, "NONE", id="13-extra-words-in-the-mention"),
    pytest.param("Dani", ["Daniel Katz", "Daniel Katz"], "TYPO_PARTIAL", id="14-nickname-close-in-spelling"),
    pytest.param("Bob", ["Robert Katz", "Robert Katz"], "NONE", id="15-nickname-not-close-in-spelling"),
    pytest.param("Sales", ["Sales"], "EXACT", id="16-team-exact"),
    pytest.param("Sale", ["Sales"], "TYPO_FULL", id="17-team-typo"),
    pytest.param("sales team", ["Sales"], "NONE", id="18-team-with-an-extra-word"),
    pytest.param("Dan", DANA, "TYPO_PARTIAL", id="19-shortened-first-name"),
]


@pytest.mark.parametrize(("mention", "names", "category"), CASES)
def test_the_agreed_cases(mention, names, category):
    _check(category, score_names(mention, names))


def test_two_people_with_the_same_first_name_score_the_same():
    # What lets #132 see there are two Danas and send no owner.
    assert score_names("Dana", ["Dana Cohen"]) == score_names("Dana", ["Dana Levi"])


def test_a_lone_first_name_scores_below_the_full_name():
    assert score_names("Dana", DANA) < score_names("Dana Cohen", DANA)


def test_a_typo_scores_below_the_exact_name():
    assert score_names("Dena Cohen", DANA) < score_names("Dana Cohen", DANA)


def test_one_word_with_no_partner_rules_the_name_out_however_well_the_rest_matches():
    assert score_names("Dana Cohen from finance", DANA) == 0.0


def test_the_best_of_a_persons_names_counts():
    assert score_names("dana.cohen", ["Someone Else", "dana.cohen"]) == 1.0


def test_a_person_with_no_names_or_a_mention_with_no_words_never_matches():
    assert score_names("Dana", []) == 0.0
    assert score_names("Dana", [None, ""]) == 0.0
    assert score_names("", DANA) == 0.0
    assert score_names("  ..  ", DANA) == 0.0


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Dana  Cohen", ("dana", "cohen")),
        ("dana.cohen", ("dana", "cohen")),
        ("Dana_Cohen", ("dana", "cohen")),
        ("DANA", ("dana",)),
        ("", ()),
        (None, ()),
    ],
)
def test_words_of_a_name(text, expected):
    assert words(text) == expected


def test_the_floor_in_config_is_below_anything_the_rule_lets_through():
    # A name that passes the word rule scores at least WORD_MATCH_MIN * PARTIAL_SCORE.
    # If min_score were higher it would become a second, hidden threshold.
    default = InboxDirectorySettings.model_fields["min_score"].default
    assert default < WORD_MATCH_MIN * PARTIAL_SCORE
    assert default < PARTIAL_SCORE

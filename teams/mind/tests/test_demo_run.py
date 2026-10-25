"""#359: the demo's runner echoes Mind's output on any console."""

import io

from demo_flow.run import echo

#: A progress bar's full block, which the Windows console's cp1255 cannot show.
_BLOCK = "█"


def test_a_character_the_console_cannot_show_is_replaced_not_raised():
    raw = io.BytesIO()
    console = io.TextIOWrapper(raw, encoding="cp1255", newline="\n")

    echo(f"[mind-landed] Loading weights: 100%|{_BLOCK * 2}| 199/199\n", console)

    assert raw.getvalue().decode("cp1255") == "[mind-landed] Loading weights: 100%|??| 199/199\n"


def test_a_line_the_console_can_show_is_written_as_it_is():
    console = io.StringIO()

    echo("[mind-api] score_publisher: started\n", console)

    assert console.getvalue() == "[mind-api] score_publisher: started\n"

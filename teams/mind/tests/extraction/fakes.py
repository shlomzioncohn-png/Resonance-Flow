"""A scripted stand-in for a real LLM provider.

Lives in the test tree on purpose: it satisfies `LLMProvider` structurally,
so production code never imports it and it can never be shipped by accident.

`answer_of` and `only` sit beside it: the answer is a list of tasks (#690),
and most tests script and read exactly one.
"""

import json

from mind.extraction.extractor import ExtractedTask, ExtractionSuccess
from mind.extraction.providers.base import LLMResponse


class ScriptedProvider:
    """Returns queued answers in order, or raises queued errors.

    Pass strings for answers and exception instances for failures, in the
    order the extractor should meet them:

        ScriptedProvider('{"bad": 1}', '{"title": "..."}')
    """

    name = "scripted"

    def __init__(self, *script: str | Exception, model: str = "scripted-model") -> None:
        self._script: list[str | Exception] = list(script)
        self.model = model
        self.calls: list[tuple[str, str]] = []

    @property
    def call_count(self) -> int:
        return len(self.calls)

    def complete(self, system: str, user: str) -> LLMResponse:
        self.calls.append((system, user))

        if not self._script:
            raise AssertionError(
                f"provider called {self.call_count} times but only "
                f"{self.call_count - 1} answers were scripted"
            )

        item = self._script.pop(0)
        if isinstance(item, Exception):
            raise item

        return LLMResponse(
            text=item,
            model=self.model,
            prompt_tokens=len(system) + len(user),
            completion_tokens=len(item),
        )


def answer_of(*tasks: dict[str, object]) -> str:
    """An answer in the shape the model is asked for: every task in one list (#690)."""

    return json.dumps({"tasks": list(tasks)})


def only(outcome: ExtractionSuccess) -> ExtractedTask:
    """The one task of an answer that was scripted with exactly one."""

    [task] = outcome.tasks
    return task

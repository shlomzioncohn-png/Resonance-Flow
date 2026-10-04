"""A scripted stand-in for a real LLM provider.

Lives in the test tree on purpose: it satisfies `LLMProvider` structurally,
so production code never imports it and it can never be shipped by accident.
"""

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

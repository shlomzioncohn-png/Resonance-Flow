"""Building the provider the configuration asks for.

`base.py` says the extractor is written against the protocol and nothing else,
so that swapping DeepSeek for Gemini is a configuration change rather than a
code change. This is what makes that sentence true: without it every caller
named a concrete class, and the claim was aspiration rather than fact.

Adding a provider here still needs Shmuel's sign-off, as `base.py` says. This
module is the place a sanctioned one gets wired in, not a licence to add more.
"""

from collections.abc import Callable

from mind.extraction.providers.base import ManagedLLMProvider
from mind.extraction.providers.deepseek import DeepSeekProvider
from mind.extraction.providers.gemini import GeminiProvider
from mind.extraction.providers.settings import configured_provider_name

#: The sanctioned providers, by the name `llm.provider` uses for them.
KNOWN_PROVIDERS: dict[str, Callable[[], ManagedLLMProvider]] = {
    "deepseek": DeepSeekProvider,
    "gemini": GeminiProvider,
}


def build_provider(name: str | None = None) -> ManagedLLMProvider:
    """The configured provider, ready to call — and ready to be closed.

    The return type says `ManagedLLMProvider` rather than `LLMProvider`
    because what comes back holds an HTTP connection: the caller owns it
    and has to close it, and the type is where that is written down.

    `name` overrides the configuration, for a script that knows which provider
    it means to exercise. Everything else reads `llm.provider`.

    There is deliberately **no default**. DeepSeek is the primary provider the
    issue names but is unreachable from some networks; Gemini is reachable but
    choosing it here would be this module inventing a policy nobody set. Either
    silent default hands somebody a provider they did not pick, and the mistake
    would surface much later as a timeout rather than here as a sentence.

    Raises `ValueError` when nothing is configured or the name is unknown. The
    provider itself raises separately when it has no key: this function picks
    one, and the provider is what insists on being usable.
    """

    chosen = (name.strip().lower() if name else None) or configured_provider_name()

    if chosen is None:
        raise ValueError(
            "no LLM provider configured: set llm.provider in the mounted config.yaml "
            f"to one of {sorted(KNOWN_PROVIDERS)}"
        )

    build = KNOWN_PROVIDERS.get(chosen)
    if build is None:
        raise ValueError(
            f"unknown LLM provider {chosen!r}: llm.provider must be one of "
            f"{sorted(KNOWN_PROVIDERS)}"
        )

    return build()

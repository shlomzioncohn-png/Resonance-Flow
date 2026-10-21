#!/usr/bin/env python3
"""Run one item through the real extraction pipeline against the live Gemini API.

The unit tests drive the provider over a mocked transport, which is the only
way to produce a 429 or a safety block on demand. What they cannot prove is
that the request shape is the one the live API actually accepts. That is what
this script is for, and it is manual on purpose: it needs a key, it spends
quota, and it must never run in CI.

It exercises the whole path, not just the HTTP call: prompts -> provider ->
parse -> repair loop -> confidence -> the outbound contract.

Usage:
    pip install -e ".[dev]"
    CONFIG_FILE_PATH=config.local.yaml SECRETS_FILE_PATH=secrets.local.yaml \\
        python scripts/smoke_gemini.py

On a network that intercepts TLS (a corporate proxy, a content filter), pass
--os-trust-store: Python verifies against certifi by default and will reject
the interception certificate even though the browser and curl accept it. The
flag does not weaken verification -- it hands verification to the operating
system, which already trusts that certificate. It needs `pip install truststore`.
"""

import argparse
import sys
from uuid import uuid4

from mind.contracts.base_enums import SourceType
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from mind.extraction.providers.factory import build_provider
from mind.extraction.providers.settings import load_gemini_settings

SAMPLE = (
    "From: dana@acme-demo.test\n"
    "Subject: Q3 report for finance\n\n"
    "Hi, finance needs the Q3 revenue report before Friday's close. I cannot start it "
    "until the data team finishes the warehouse backfill. Also, could you book the board "
    "room for Monday's review? Message id: MSG-4471"
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--os-trust-store",
        action="store_true",
        help="verify TLS against the OS trust store instead of certifi "
        "(for networks that intercept TLS); requires the truststore package",
    )
    args = parser.parse_args()

    if args.os_trust_store:
        import truststore

        truststore.inject_into_ssl()
        print("TLS: verifying against the OS trust store")

    settings = load_gemini_settings()
    if not settings.configured:
        print(
            "no Gemini api_key found. Point CONFIG_FILE_PATH and SECRETS_FILE_PATH at "
            "local config/secrets files holding llm.gemini.*",
            file=sys.stderr,
        )
        return 1

    print(f"model: {settings.model}")
    print(f"endpoint: {settings.generate_url}")

    # Named explicitly: this script exists to exercise Gemini, whatever
    # llm.provider happens to say.
    with build_provider("gemini") as provider:
        outcome = BasicExtractor(provider).extract(
            SAMPLE, SourceType.EMAIL, item_key="smoke-test/sample-email"
        )

    if not isinstance(outcome, ExtractionSuccess):
        print(f"\nFAILED: {outcome.reason} - {outcome.detail}", file=sys.stderr)
        print(f"last answer: {outcome.last_output}", file=sys.stderr)
        return 1

    print(f"\nschema attempts: {outcome.schema_attempts}  provider calls: {outcome.provider_calls}")
    print(f"tasks: {len(outcome.tasks)}")
    if not outcome.tasks:
        # A valid answer in general, but not for SAMPLE, which asks for two
        # things. Passing here would hide the one regression #690 invites: a
        # model told that "no task" is allowed, answering it everywhere.
        print("\nFAILED: no task found in a sample that contains two", file=sys.stderr)
        return 1
    for number, task in enumerate(outcome.tasks, start=1):
        print(f"\n[{number}] {task.fields.title}  confidence: {task.confidence.value}")
        for penalty in task.confidence.penalties:
            print(f"  -{penalty.effective:<8} {penalty.reason}")

    # The proposals Inbox would receive, one per task. tenant_id is invented
    # here: outside a smoke test it comes from the intake event, never from
    # this stage.
    proposals = to_task_creation_requested(
        outcome, tenant_id=uuid4(), bridge_source_ref=None, bridge_correlation_id=uuid4()
    )
    for proposal in proposals:
        print("\nTaskCreationRequested:")
        print(proposal.model_dump_json(indent=2, exclude={"tenant_id"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

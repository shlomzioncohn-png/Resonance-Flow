package com.rnd.bridge.raw.model;

/**
 * How much care the item's content calls for - a level rather than a list of kinds, since a
 * consumer acts on "how careful" (e.g. never forward HIGH to an external LLM), not on which
 * kind. Bridge only flags it; what to do about a sensitive item is the consumer's decision.
 */
public enum Sensitivity {
    /** Nothing sensitive - a planning meeting, a presentation, a news article. */
    NONE,
    /** Ordinary personal or business details - names and emails, a vendor invoice, a project budget. */
    LOW,
    /** Harmful if exposed - medical, an individual's termination or salary, litigation, bank details, national ID numbers. */
    HIGH
}

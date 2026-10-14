"""Model-call tracking -- the single place every future AI model
integration records how long a call took and what it cost.

See tracking.py for record_call(). Every module that calls a model
(extraction, embeddings, scoring, multimodal) must route through it so we
get one consistent view of latency and spend, instead of each module
inventing its own logging.
"""

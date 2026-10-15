# task_creation

Asking Inbox to create a task, and recognising its reply (#353). Mind never
creates a task: Inbox is the only team that issues a task id, so Mind asks.

```
extraction ─► to_task_creation_requested ─► send_creation_request ─► task.creation.requested ─► Inbox
                                                   │ registers                                    │
                                                   ▼                                              ▼
                                             PendingStore ◄── handle_task_created_reply ◄── task.created
                                                   │                    │
                                           report_overdue        R3's index write
```

| file | what it does |
|---|---|
| `event.py` | `TaskCreationRequested` → the `TaskCreationRequestedEvent` Inbox reads |
| `correlation.py` | the request's `correlation_id`, derived so a retry repeats it |
| `pending.py` | `PendingStore`, and its in-memory implementation |
| `send.py` | build, register, publish — in that order |
| `reply.py` | match `task.created` to its request, then index the task |
| `overdue.py` | report requests Inbox has not answered |
| `flow.py` | `TaskCreationFlow`: one object for both sides, so they share one store |
| `listener.py` | the task.created listener and the overdue timer |

## The source_ref Inbox receives

Set in `extraction/assembly.py`. Inbox requires it (`@NotBlank`), and its UI
builds the link to the source from `source_type` + `source_ref`, so it is
never blank and never misleading:

1. Bridge's own `source_ref` — the source system's id, the only value a link
   can be built from;
2. otherwise `bridge:<Bridge's correlation_id>` — always present; the prefix
   says there is no external link.

What extraction reads from the content is not a candidate: a pointer the text
mentions is not the item's own id, and a link built from it leads elsewhere.

## Translating to the wire

Not a second payload shape: the contract is `TaskCreationRequested`, the wire
format is the registered `shared/proto`, and `event.py` only translates.

- **None stays unset.** Protobuf reads an unset field as 0, so None and 0 only
  survive as different values if a None field is never touched — and Inbox
  validates on the difference (`urgency` 0 is rejected, absent is not).
- **Enums gain their prefix** (`OPERATIONAL` → `TASK_TIER_OPERATIONAL`). A value
  missing from `shared/proto` fails here, never as `UNSPECIFIED`.
- **A blank `source_ref` fails here.** Inbox would reject it into its error
  topic, the task would never exist, and Mind would wait for a reply that never
  comes.
- Left unset on purpose: `event_id`, `project_id`, `created_by_user_id`
  (no source for them), `status` (Inbox starts tasks as `NEW`), `assignees`
  (a candidate owner is a suggestion; there it would become an assignment —
  agreed with Inbox).

## The correlation_id

`uuid5` over Bridge's item id and the task's number within the item. Inbox
de-duplicates on it, so a retry must carry the same one, and a derived id
repeats itself after a crash with nothing remembered.

- **Identity, not content.** Re-extraction rewords the content (two ids for one
  item: two tasks); two people can write the same words (one id for two items:
  a task lost).
- **No revision.** One item makes one task. A later revision is an update to
  it — a different flow, which will find the revision kept beside the request.
- **The namespace is fixed for ever.** Changing it re-sends every request as a
  new one. A test pins a known value, because no other test can see across a
  restart.

## Order: register, then publish; index, then forget

- **Registered before publishing**, so a fast reply always finds its request.
  Registering after would let a reply that beats the bookkeeping be dropped as
  unknown, in silence.
- **The message is built before registering**, so a request Inbox would reject
  leaves nothing behind.
- **A failed publish takes back only a registration it made.** One it found
  is kept: an earlier attempt may have arrived.
- **A reply is indexed before it is forgotten.** If the write fails, the error
  propagates, Kafka redelivers, and the redelivery must still find its request.
- **A reply that matches nothing is ignored**, at INFO: tasks anyone else
  creates arrive on the same topic.

Indexing uses R3's `handle_task_created` unchanged — the same embedding and
`upsert_if_absent`, so whichever consumer writes first, the other does not
overwrite it. Searching for duplicates happens *before* a request is sent
(R3's `nearest_tasks`); writing happens only after Inbox confirms, so the index
holds only tasks that exist.

`handle_task_created_reply` returns a `MatchedReply` — the real task id and
what Mind knew when it asked, revision included. It is the hook for #355
(dependencies) and for a later update flow.

## A reply that never comes

`report_overdue` warns once about each request older than `max_wait`
(default **10 minutes** — Inbox answers in seconds, so this means broken, not
slow) and keeps it, so a late reply still matches. Every matched reply logs how
long it took; once real latencies exist, the limit can be set from them (about
twice the usual) instead of this guess.

## The pending store, and why the interface is async

`PendingStore` is async although memory never waits, because a database call
must not block the loop it is awaited on. The sender, the reply handler and the
overdue check only ever see the interface.

It does not make a Postgres store a drop-in, though. The store is used from
three threads, each on its own event loop (pipeline, listener, timer), and an
async Postgres pool belongs to the loop that opened it. A Postgres store has to
work from several loops — a pool per loop, opened on first use in each, or a
connection per call — or the three sides have to share one loop.

The in-memory `PendingRequests` has two limits, and both are the reason for a
database one:

- **The sender and the listener must run in one process.** The sender is the
  #349 pipeline; the listener consumes `task.created`. In separate processes
  the listener sees an empty store and drops every reply as unknown.
- **A restart forgets it.** R3's consumer still indexes every task, but a reply
  that arrives after a restart is not matched.

A table would also be what a later update flow needs: a lasting record of
which item became which task, at which revision.

## Running it

The pipeline that calls `send` is #349's, and does not exist yet, so nothing
here starts on its own. Whoever builds it composes one `TaskCreationFlow` and
runs the listener and the timer beside it, **in the same process**:

```python
listener_loop = asyncio.new_event_loop()
index = PgVectorStore(get_settings().vector_store)
listener_loop.run_until_complete(index.open())   # on the loop that will use it

pending = PendingRequests()          # one store, shared by everything below
flow = TaskCreationFlow(
    producer=ProtobufProducer(),
    index=index,
    pending=pending,
    on_matched=publish_dependencies,  # #355: hears of each real task id, maybe twice
)

# A listener that has given up must take the process with it, so it is
# restarted — the same guard as R3's runner (_die_on_thread_crash).
threading.excepthook = lambda args: os._exit(1)

stop = threading.Event()
threading.Thread(target=run_task_created_listener, args=(flow, listener_loop)).start()
threading.Thread(target=run_overdue_timer, args=(flow, stop)).start()

# ... and in the #349 pipeline, for each task extracted from a Bridge item:
await flow.send(proposal, bridge_correlation_id=event.correlation_id, revision=event.revision)
```

- **The listener has its own consumer group** (`mind-task-creation`). R3's
  index consumer reads `task.created` too, and within one group each message
  reaches one member — so sharing R3's group would split the messages between
  them, in silence: replies unmatched here, other teams' tasks never indexed
  there.
- **The timer runs on its own thread and loop.** Requests go overdue when
  Inbox is down, which is exactly when no `task.created` arrives, so the check
  cannot ride on the listener.
- **A failure in the listener is retried, then fatal.** `ProtobufConsumer.run`
  closes the consumer on an error, so the listener reconnects itself — waits
  doubling from 1 s to 60 s — and Kafka hands the uncommitted message to the
  new connection, while the in-memory store survives to match it. After 5
  failures in a row (a handled message resets the count) it lets the error
  out, and the excepthook above exits the process to be restarted. A message
  that fails every time therefore ends in a visible crash loop, not silence.
- **The timer is the opposite:** it logs a failed check and carries on, so it
  never stops reporting in silence.
- **Only the timer has a stop signal.** The listener blocks in Kafka's poll and
  ends with the process; a message cut off mid-way was not committed and is
  redelivered.
- `index` is the vector store the listener writes through, opened on the
  listener's loop (a pool belongs to the loop that opened it).
- **`on_matched` runs at least once per task, and may run twice**: the
  request is forgotten only after it returns, so a failure is retried on
  redelivery rather than lost. It must not mind hearing of a task again.
- **One producer, several threads.** `send` publishes on a worker thread so the
  pipeline's loop is not blocked, so concurrent sends share the
  `ProtobufProducer`. confluent-kafka's producer is thread-safe and each
  publish keeps its delivery result in its own call; the serializer cache can
  at worst build one twice.

## Not yet in

- **Three suggestion fields** — `effort_estimate_confidence`,
  `suggested_owner_user_id`, `suggested_owner_confidence`. Inbox accepts them
  (#747, fields 19–21), but only in its own copy of the proto on the
  `inbox-team` branch. Mind generates from `shared/proto` on `main`, so they
  join once they reach it.
- **ZOOM and BIGBLUEBUTTON** as task source types (Inbox #749) — same reason.
  Until then intake maps both to `MEETING`. A test fails if a value is added to
  Mind's `SourceType` before it reaches `shared/proto`.

- **The extracted `source_ref` still counts toward confidence.** Inbox now gets
  Bridge's, but `confidence.py` still deducts for a missing or invented
  extracted one. Changing that moves #350's scores and calibration, so it is
  left for the same follow-up as whether to ask the model for it at all.

## Running the tests

```
pytest tests/test_task_creation_flow.py -v   # the issue's round trip
pytest tests/test_task_creation_*.py tests/extraction/test_assembly.py -q
```

The flow and listener tests replace the embedding with a fixed vector: they
test the flow, and the real model downloads on first use.

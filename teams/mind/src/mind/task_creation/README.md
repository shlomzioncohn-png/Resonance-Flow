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
- **A blank `title` or `source_ref` fails here.** Inbox would reject it
  (`@NotBlank`) into its error topic, the task would never exist, and Mind
  would wait for a reply that never comes.
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
- **A retry that went out renews its entry**: the overdue clock restarts, the
  revision is the one Inbox now holds, and it may be reported again. A failed
  retry changes nothing — nothing reached Inbox.
- **Publishing runs on a worker thread**, so `publish()` waiting for Kafka's
  ack does not block the caller's event loop; it is still awaited, so the
  order above holds.
- **A reply is indexed, and `on_matched` told, before it is forgotten.** If
  either fails, the error propagates, Kafka redelivers, and the redelivery must
  still find its request — so `on_matched` runs at least once, maybe twice,
  and must not mind hearing of a task again.
- **Replies Mind did not ask for are ignored**, at three levels: no
  `correlation_id` header (anyone else's task) at DEBUG; a header that is not a
  UUID (a format changed somewhere) at WARNING, with what arrived; a valid id
  matching nothing pending (a restart, or a redelivery already matched) at INFO.

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
slow) and keeps it, so a late reply still matches. A request is marked only
past the wait (marking a young one would use up the report it is owed later)
and only while still pending (a reply matched during the check earns no
warning). Every matched reply logs how
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

### Three threads, one store

The pipeline, the listener and the timer each touch the store from their own
thread, so every method of `PendingRequests` holds one `threading.Lock` — an
asyncio lock would not do, each thread runs its own loop. Two writes depend on
a check and must not let another thread act in between:

- **`renew` acts only on an entry still pending.** The retry's reply can be
  matched, and the entry forgotten, before the sender renews it; bringing it
  back would leave it waiting for ever and reported overdue for a task that
  exists.
- **`mark_reported` acts only on an entry still pending**, for the same reason
  from the timer's side.

A Postgres store gets the same guarantees from single statements
(`UPDATE ... WHERE ... AND reported_at IS NULL`).

## Running it

`ingestion/landed_consumer_runner.py` (#919) is the process that does this:
the #349 pipeline calls `send`, and it composes one `TaskCreationFlow` and
runs the listener and the timer beside it, **in the same process**. The
composition, in short:

```python
loop = asyncio.new_event_loop()      # the listener calls run_until_complete on this, per message
index = PgVectorStore(get_settings().vector_store)
loop.run_until_complete(index.open())   # on the loop that will use it

producer = ProtobufProducer()
pending = PendingRequests()          # one store, shared by everything below

# #355: resolves dependency_mentions and publishes task.dependencies.proposed,
# once, when task.created confirms the task exists. No retry -- see
# dependencies/README.md. Shares this same producer and index -- no second
# store or connection needed.
sink = KafkaDependencySink(producer)
publisher = DependencyPublisher(store=index, sink=sink)
adapter = DependencyAdapter(publisher)

flow = TaskCreationFlow(
    producer=producer,
    index=index,
    pending=pending,
    on_matched=adapter.on_task_created,        # #355: hears of each real task id, maybe twice
)

# A listener that has given up must take the process with it, so it is
# restarted — the same guard as R3's runner (_die_on_thread_crash).
threading.excepthook = lambda args: os._exit(1)

stop = threading.Event()
threading.Thread(target=run_task_created_listener, args=(flow, loop)).start()
threading.Thread(target=run_overdue_timer, args=(flow, stop)).start()

# ... and in the #349 pipeline, for each task extracted from a Bridge item:
await flow.send(proposal, bridge_correlation_id=event.correlation_id, revision=event.revision)
```

- **The listener and the timer each have their own loop and thread,
  entirely independent of each other.** They do share `pending`
  (`PendingRequests`), from two threads -- but that's `PendingRequests`'
  own job: every method takes its own lock (see "Three threads, one
  store" above), so this was already safe before either of them had a
  loop of its own. Neither's *async* work depends on the other's anymore,
  so neither can delay the other's handling. `on_task_created`
  (`adapter.on_task_created`, via `on_matched`) does all of #355's work
  inline, synchronously, as part of handling that one message — nothing is
  scheduled to run afterward, so the listener's loop never needs to keep
  running between messages; `loop.run_until_complete` per message is all it
  takes.
- **The listener has its own consumer group** (`mind-task-creation`). R3's
  index consumer reads `task.created` too, and within one group each message
  reaches one member — so sharing R3's group would split the messages between
  them, in silence: replies unmatched here, other teams' tasks never indexed
  there.
- **A failure in the listener is retried, then fatal.** `ProtobufConsumer.run`
  closes the consumer on an error, so the listener reconnects itself — waits
  doubling from 1 s to 60 s — and Kafka hands the uncommitted message to the
  new connection, while the in-memory store survives to match it. The limit
  is **time, not a count**: no message succeeds during an outage, so a count
  ran out in seconds and the restart wiped the store. Only after failing for
  15 minutes (`MAX_OUTAGE`; a handled message ends the outage) does it let the
  error out, for the excepthook above to exit the process and have it
  restarted. A message that fails every time therefore ends in a visible crash
  loop, not silence. A connection that was **quiet** — up, with no message —
  for longer than the longest wait (60 s) was a recovery even with no message
  to say so, so its failure starts a new outage. Time spent handling the message
  that failed does not count as quiet: a message that takes 90 s to fail every
  time arrives at once and stays one outage, rather than looking like a
  recovery on each attempt and never being given up on.
- **The timer is the opposite:** it logs a failed check and carries on, so it
  never stops reporting in silence.
- **Only the timer has a stop signal.** The listener blocks in Kafka's poll and
  ends with the process; a message cut off mid-way was not committed and is
  redelivered.
- `index` is the vector store the listener writes through, opened on the
  listener's loop (a pool belongs to the loop that opened it).
- **One producer, several threads.** Publishing runs off the loop (see
  "Order" above), so concurrent sends share the `ProtobufProducer`.
  confluent-kafka's producer is thread-safe and each publish keeps its
  delivery result in its own call; the serializer cache can at worst build
  one twice.

## The owner

Mind suggests an owner; it never assigns one (#132). What reaches Inbox is
`suggested_owner_user_id` with its confidence, or neither.

- **Extraction gives a name or "the recipient", never an address.**
  `owner_mention` is a name the content writes; `owner_is_recipient` says an
  email asks the person it is sent to. That person's address is taken from
  the email record, and only when there is one recipient: a model copying it
  off the To line could as well copy the From line's. `owner.what_to_look_up`
  turns the two into the one string the directory is asked for, or none.
  Each recipient keeps its display name beside the address ("Dana Cohen
  <dana@…>"); the directory is asked by the address alone.
- **The recipient and a name must be one person.** Given both, the name must
  be spelled from the recipient's display name or the address before its "@"
  ("Hi Dana, please sign" sent to Dana): then the address is surer than a
  first name. If they are two people ("Yossi Levi will sign it" sent to Dana)
  one answer is wrong, and the address would send it at 1.0, an exact match:
  no owner, logged. Without one recipient (a chat, several) the name is looked
  up instead, and so it is when the one recipient is the sender (an email to
  oneself). An address is found only once #131 matches addresses exactly, as
  agreed with its author; #131's draft matches words, and an address matches
  nobody there.
- **A directory that fails costs only the owner.** #131 promises never to
  raise; if it does, the task goes with no owner, rather than the message
  failing and coming back for ever. The error is logged by its type only, as
  its message may hold the name asked for; the trace is at DEBUG.
- **The sender is never the owner.** The prompt says so, but a sender who signs
  "Thanks, Avi" has written the name, so grounding keeps it, and a live run
  returned it. `owner.is_the_sender` drops a name whose every word is the
  sender's: the display name and the address before its "@", never the
  domain ("Avi" for `avi.katz@…`). One way only, so another person with the
  sender's first name ("Dana Levi" for `dana@…`) is kept. A heuristic: `ak@`
  for Avraham is missed, and so is "Lisa Grant" for a sender the record knows
  only as "Lisa"; a name with a word of its own ("Avi Levi") is someone else.

### Choosing one user (`owner.choose_owner`)

The name or address is looked up in Inbox's directory (#131,
`find_candidates`), which gives users and teams with a similarity score from 0
to 1, best first. The score says how alike two names are, not how sure Mind is,
so the rule that turns it into `suggested_owner_confidence` is this one:

1. **Teams are left out.** Inbox has no field for a suggested team, so a team
   neither wins nor stands in a user's way: a team and a user with the same
   name give the user. A name that matches only a team ("someone from Sales")
   gives no owner, logged.
2. **The best user needs `MIN_OWNER_SCORE` (0.70)**, which a first name alone
   reaches. A higher bar would leave chats with almost no owner, since a
   speaker is labelled by first name ("Malki"). Below it no owner, logged.
3. **A second user within `TIE_MARGIN` (0.05) is a tie, and a tie is no
   owner,** logged, even when that second user is under the minimum.
4. **The confidence is the winner's score.**

What that gives, with #131's scores:

| The text says | Inbox has | Sent |
|---|---|---|
| `Dana Cohen`, or her address | one Dana Cohen | her, 1.0 |
| `Dana C.` | Dana Cohen | her, 0.95 |
| `Dena Cohen` (a typo) | Dana Cohen | her, 0.88 |
| `Dana` | one Dana | her, 0.70 |
| `Dana` | two Danas | nobody: a tie |
| `Dana Cohen` | Dana Cohen and Dana Levi | Dana Cohen, 1.0 (Levi is 0.70) |
| `Dan`, `Dani`, `Dena` | a Dana, a Daniel | nobody: 0.52 to 0.60 |
| `Sales` | the team Sales | nobody: a team |

Known limit: `Dana` when the Dana meant is from outside and Inbox has one
Dana of its own suggests the wrong person, at 0.70. The low confidence is the
warning to whoever approves it in Inbox.

Not yet measured on real names: the numbers rest on #131's scores, not on a
labelled set. Revisit them when one exists.

## Not yet in

- **No directory in the runner yet** (#132). `CreationPipeline` looks the
  owner up when it is given a directory, and `event.py` sends fields 20 and
  21, but #131's `InboxDirectory` is not on `main`, so the landed runner
  passes none and no owner is sent.
- **Real Gmail messages have no sender or recipients yet.** Bridge lands the
  Gmail API message, not the record (`ingestion/email_body.py`), so its text
  goes as it is: an email's recipient is never looked up, and its sender is
  not ruled out, until Bridge lands the plain record.
- **`effort_estimate_confidence`** (Inbox #747, field 19) is in `shared/proto`
  now but not mapped here yet.
- **ZOOM and BIGBLUEBUTTON** as task source types (Inbox #749) — same reason.
  Until then intake maps both to `MEETING`. A test fails if a value is added to
  Mind's `SourceType` before it reaches `shared/proto`.
- **The extracted `source_ref` still counts toward confidence.** Inbox now gets
  Bridge's, but `confidence.py` still deducts for a missing or invented
  extracted one. Changing that moves #350's scores and calibration, so it is
  left for the same follow-up as whether to ask the model for it at all.

## Settled with Inbox

Raised in review, answered by Inbox (Esti Carmel, October 2026):

- **One source_ref, several tasks.** Inbox now de-duplicates on
  `correlation_id`, not on (tenant, `source_type`, `source_ref`), so several
  tasks from one Bridge item each get created (#690).
- **A duplicate request gets no reply.** Inbox agreed to re-emit `task.created`
  for the existing task; not implemented on their side yet. Until it is, a
  re-sent request waits for ever (and is reported overdue once), and its entry
  stays in the in-memory store until a restart; the re-emitted reply will clear
  it, so no expiry is built.

## Running the tests

```
pytest tests/test_task_creation_flow.py -v   # the issue's round trip
pytest tests/test_task_creation_*.py tests/extraction/test_assembly.py -q
```

The flow and listener tests replace the embedding with a fixed vector: they
test the flow, and the real model downloads on first use.

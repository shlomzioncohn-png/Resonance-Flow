"""Read-only clients for other teams' services (#131).

Where Mind asks another team's service a question over HTTP, rather than
receiving an event. Today that is Inbox's Users/Teams lookup, used to turn a
name found in text into a user or team id (see inbox_directory.py). Nothing
here writes to another team's service.
"""

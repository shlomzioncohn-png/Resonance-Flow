// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build. (Kept in one file here purely to keep the example
// set short — in real code each exception gets its own file.)
package com.rnd.inbox.exception;

public abstract class InboxException extends RuntimeException {
    protected InboxException(String message) {
        super(message);
    }
}

class TaskNotFoundException extends InboxException {
    public TaskNotFoundException(String message) {
        super(message);
    }
}

class InvalidTaskStateException extends InboxException {
    public InvalidTaskStateException(String message) {
        super(message);
    }
}

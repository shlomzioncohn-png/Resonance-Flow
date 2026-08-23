package com.rnd.chain.dto;

// TODO: import the Inbox type once the Inbox team publishes its package,
// e.g. import com.inbox.Inbox;

public class TaskDependency {

    // Identifier type follows Inbox: both ends of the edge use the task id
    // exactly as Inbox sends it, with no conversion on our side.
    private Inbox.TaskId taskId;            // from Mind
    private Inbox.TaskId dependsOnTaskId;   // from Mind

    public TaskDependency() {
    }

    public TaskDependency(Inbox.TaskId taskId, Inbox.TaskId dependsOnTaskId) {
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
    }

    public Inbox.TaskId getTaskId() {
        return taskId;
    }

    public void setTaskId(Inbox.TaskId taskId) {
        this.taskId = taskId;
    }

    public Inbox.TaskId getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public void setDependsOnTaskId(Inbox.TaskId dependsOnTaskId) {
        this.dependsOnTaskId = dependsOnTaskId;
    }
}

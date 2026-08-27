package com.rnd.chain.dto;
import java.util.UUID;
// TODO: import the Inbox type once the Inbox team publishes its package,
// e.g. import com.inbox.Inbox;

public class TaskDependency {

    // Identifier type follows Inbox: both ends of the edge use the task id
    // exactly as Inbox sends it, with no conversion on our side.
    private UUID taskId;            // from Mind
    private UUID dependsOnTaskId;   // from Mind

    public TaskDependency() {
    }

    public TaskDependency(UUID taskId, UUID dependsOnTaskId) {
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public void setDependsOnTaskId(UUID dependsOnTaskId) {
        this.dependsOnTaskId = dependsOnTaskId;
    }
}
package com.rnd.bridge.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class Task {
    private String id;
    private String title;
    private String description;
    private SourceType sourceType;
    private String sourceRef;
    private String owner;
    private String roleContext;
    private Tier tier;
    private Integer urgency;
    private Double effortEstimateMinutes;
    private Instant deadline;
    private String parentTaskId;
    private List<String> dependsOn;
    private List<String> constraints;
    private Double confidence;
    private ExtractionProvenance extractionProvenance;
    private TaskStatus status;

    public Task() {
    }

    public Task(String id, String title, String description, SourceType sourceType, String sourceRef,
                String owner, String roleContext, Tier tier, Integer urgency, Double effortEstimateMinutes,
                Instant deadline, String parentTaskId, List<String> dependsOn, List<String> constraints,
                Double confidence, ExtractionProvenance extractionProvenance, TaskStatus status) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.owner = owner;
        this.roleContext = roleContext;
        this.tier = tier;
        this.urgency = urgency;
        this.effortEstimateMinutes = effortEstimateMinutes;
        this.deadline = deadline;
        this.parentTaskId = parentTaskId;
        this.dependsOn = dependsOn;
        this.constraints = constraints;
        this.confidence = confidence;
        this.extractionProvenance = extractionProvenance;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRoleContext() {
        return roleContext;
    }

    public void setRoleContext(String roleContext) {
        this.roleContext = roleContext;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public Integer getUrgency() {
        return urgency;
    }

    public void setUrgency(Integer urgency) {
        this.urgency = urgency;
    }

    public Double getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public void setEffortEstimateMinutes(Double effortEstimateMinutes) {
        this.effortEstimateMinutes = effortEstimateMinutes;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public String getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(String parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn;
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<String> constraints) {
        this.constraints = constraints;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public ExtractionProvenance getExtractionProvenance() {
        return extractionProvenance;
    }

    public void setExtractionProvenance(ExtractionProvenance extractionProvenance) {
        this.extractionProvenance = extractionProvenance;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Task{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", sourceType=" + sourceType +
                ", sourceRef='" + sourceRef + '\'' +
                ", owner='" + owner + '\'' +
                ", roleContext='" + roleContext + '\'' +
                ", tier=" + tier +
                ", urgency=" + urgency +
                ", effortEstimateMinutes=" + effortEstimateMinutes +
                ", deadline=" + deadline +
                ", parentTaskId='" + parentTaskId + '\'' +
                ", dependsOn=" + dependsOn +
                ", constraints=" + constraints +
                ", confidence=" + confidence +
                ", extractionProvenance=" + extractionProvenance +
                ", status=" + status +
                '}';
    }
}

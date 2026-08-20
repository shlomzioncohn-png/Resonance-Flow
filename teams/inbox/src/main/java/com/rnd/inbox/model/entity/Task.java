package com.rnd.inbox.model.entity;

import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskStatus;
import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Version;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@TypeDef(name = "pgsql_enum", typeClass = PostgreSQLEnumType.class)
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = true)
    private User owner;

    @ManyToOne
    @JoinColumn(name = "dependency_task_id", nullable = true)
    private Task dependencyTask;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Type(type = "pgsql_enum")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "task_status")
    private TaskStatus status;

    @Column(name = "priority_score")
    private Double priorityScore;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Type(type = "pgsql_enum")
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, columnDefinition = "task_source_type")
    private SourceType sourceType;

    @Column(name = "source_ref_id")
    private String sourceRefId;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    @Column(name = "due_date")
    private Instant  dueDate;

    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb",nullable = false)
    private Map<String, Object> metadata=new HashMap<>();

    @Version
    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Task() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public Task getDependencyTask() {
        return dependencyTask;
    }

    public void setDependencyTask(Task dependencyTask) {
        this.dependencyTask = dependencyTask;
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

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Double getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(Double priorityScore) {
        this.priorityScore = priorityScore;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRefId() {
        return sourceRefId;
    }

    public void setSourceRefId(String sourceRefId) {
        this.sourceRefId = sourceRefId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Integer getEstimatedDurationMinutes() {
        return estimatedDurationMinutes;
    }

    public void setEstimatedDurationMinutes(Integer estimatedDurationMinutes) {
        this.estimatedDurationMinutes = estimatedDurationMinutes;
    }

    public Instant  getDueDate() {
        return dueDate;
    }

    public void setDueDate(Instant  dueDate) {
        this.dueDate = dueDate;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Task)) return false;
        Task that = (Task) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

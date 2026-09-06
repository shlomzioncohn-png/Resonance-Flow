package com.rnd.inbox.model.entity;

import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;
import com.rnd.inbox.model.type.LowercasePostgreSQLEnumJdbcType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.Type;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = true)
    private Project project;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, columnDefinition = "task_source_type")
    private SourceType sourceType;

    @Column(name = "source_ref")
    private String sourceRef;

    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", columnDefinition = "task_tier")
    private TaskTier tier;

    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Enumerated(EnumType.STRING)
    @Column(name = "role_context", columnDefinition = "task_role_context")
    private TaskRoleContext roleContext;

    @Column
    private Short urgency;

    @Column(name = "effort_estimate_minutes")
    private Integer effortEstimateMinutes;

    @Column
    private Instant deadline;

    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "task_status")
    private TaskStatus status;

    @Column(name = "is_available", nullable = false)
    private boolean isAvailable;

    @Column(name = "priority_score")
    private Float priorityScore;

    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_provenance", nullable = false, columnDefinition = "task_extraction_provenance")
    private TaskExtractionProvenance extractionProvenance;

    @Column
    private Float confidence;

    @Column(name = "effort_estimate_confidence")
    private Float effortEstimateConfidence;

    @ManyToOne
    @JoinColumn(name = "suggested_owner_user_id")
    private User suggestedOwnerUser;

    @Column(name = "suggested_owner_confidence")
    private Float suggestedOwnerConfidence;

    @ManyToOne
    @JoinColumn(name = "created_by", nullable = true)
    private User createdBy;


    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public Task() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
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

    public TaskTier getTier() {
        return tier;
    }

    public void setTier(TaskTier tier) {
        this.tier = tier;
    }

    public TaskRoleContext getRoleContext() {
        return roleContext;
    }

    public void setRoleContext(TaskRoleContext roleContext) {
        this.roleContext = roleContext;
    }

    public Short getUrgency() {
        return urgency;
    }

    public void setUrgency(Short urgency) {
        this.urgency = urgency;
    }

    public Integer getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public void setEffortEstimateMinutes(Integer effortEstimateMinutes) {
        this.effortEstimateMinutes = effortEstimateMinutes;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public void setAvailable(boolean available) {
        isAvailable = available;
    }

    public Float getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(Float priorityScore) {
        this.priorityScore = priorityScore;
    }

    public Instant getSnoozedUntil() {
        return snoozedUntil;
    }

    public void setSnoozedUntil(Instant snoozedUntil) {
        this.snoozedUntil = snoozedUntil;
    }

    public TaskExtractionProvenance getExtractionProvenance() {
        return extractionProvenance;
    }

    public void setExtractionProvenance(TaskExtractionProvenance extractionProvenance) {
        this.extractionProvenance = extractionProvenance;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public Float getEffortEstimateConfidence() {
        return effortEstimateConfidence;
    }

    public void setEffortEstimateConfidence(Float effortEstimateConfidence) {
        this.effortEstimateConfidence = effortEstimateConfidence;
    }

    public User getSuggestedOwnerUser() {
        return suggestedOwnerUser;
    }

    public void setSuggestedOwnerUser(User suggestedOwnerUser) {
        this.suggestedOwnerUser = suggestedOwnerUser;
    }

    public Float getSuggestedOwnerConfidence() {
        return suggestedOwnerConfidence;
    }

    public void setSuggestedOwnerConfidence(Float suggestedOwnerConfidence) {
        this.suggestedOwnerConfidence = suggestedOwnerConfidence;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
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

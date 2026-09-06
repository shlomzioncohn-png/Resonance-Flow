package com.rnd.inbox.mapper;

import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Project;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMapperTest {

    private final TaskMapper taskMapper = new TaskMapper();

    @Test
    void applyUpdate_copiesTitleDescriptionAndStatus() {
        Task existing = existingTask();
        Task changes = new Task();
        changes.setTitle("Updated title");
        changes.setDescription("Updated description");
        changes.setStatus(TaskStatus.IN_PROGRESS);

        taskMapper.applyUpdate(existing, changes);

        assertThat(existing.getTitle()).isEqualTo("Updated title");
        assertThat(existing.getDescription()).isEqualTo("Updated description");
        assertThat(existing.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void applyUpdate_copiesClassificationAndSchedulingFields() {
        Task existing = existingTask();
        Project newProject = new Project();
        newProject.setId(UUID.randomUUID());
        Instant deadline = Instant.parse("2026-09-10T12:00:00Z");
        Task changes = new Task();
        changes.setTier(TaskTier.STRATEGIC);
        changes.setRoleContext(TaskRoleContext.MANAGER);
        changes.setUrgency((short) 4);
        changes.setEffortEstimateMinutes(90);
        changes.setDeadline(deadline);
        changes.setProject(newProject);

        taskMapper.applyUpdate(existing, changes);

        assertThat(existing.getTier()).isEqualTo(TaskTier.STRATEGIC);
        assertThat(existing.getRoleContext()).isEqualTo(TaskRoleContext.MANAGER);
        assertThat(existing.getUrgency()).isEqualTo((short) 4);
        assertThat(existing.getEffortEstimateMinutes()).isEqualTo(90);
        assertThat(existing.getDeadline()).isEqualTo(deadline);
        assertThat(existing.getProject()).isSameAs(newProject);
    }

    @Test
    void applyUpdate_copiesMlSuggestionFields() {
        Task existing = existingTask();
        User suggestedOwner = new User();
        suggestedOwner.setId(UUID.randomUUID());
        Task changes = new Task();
        changes.setEffortEstimateConfidence(0.82f);
        changes.setSuggestedOwnerUser(suggestedOwner);
        changes.setSuggestedOwnerConfidence(0.91f);

        taskMapper.applyUpdate(existing, changes);

        assertThat(existing.getEffortEstimateConfidence()).isEqualTo(0.82f);
        assertThat(existing.getSuggestedOwnerUser()).isSameAs(suggestedOwner);
        assertThat(existing.getSuggestedOwnerConfidence()).isEqualTo(0.91f);
    }

    @Test
    void applyUpdate_neverTouchesIdentityFields() {
        Task existing = existingTask();
        UUID originalId = existing.getId();
        Organization originalOrganization = existing.getOrganization();
        SourceType originalSourceType = existing.getSourceType();
        String originalSourceRef = existing.getSourceRef();
        User originalCreatedBy = existing.getCreatedBy();
        Instant originalCreatedAt = existing.getCreatedAt();

        Task changes = new Task();
        changes.setTitle("Different title, should not affect identity fields");

        taskMapper.applyUpdate(existing, changes);

        assertThat(existing.getId()).isEqualTo(originalId);
        assertThat(existing.getOrganization()).isSameAs(originalOrganization);
        assertThat(existing.getSourceType()).isEqualTo(originalSourceType);
        assertThat(existing.getSourceRef()).isEqualTo(originalSourceRef);
        assertThat(existing.getCreatedBy()).isSameAs(originalCreatedBy);
        assertThat(existing.getCreatedAt()).isEqualTo(originalCreatedAt);
    }

    private Task existingTask() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        User createdBy = new User();
        createdBy.setId(UUID.randomUUID());

        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrganization(organization);
        task.setTitle("Original title");
        task.setDescription("Original description");
        task.setSourceType(SourceType.GITHUB_ISSUE);
        task.setSourceRef("github:owner/repo:issue:42");
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.ETL);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(Instant.parse("2026-08-31T10:15:30Z"));
        return task;
    }
}

package com.rnd.inbox.bridge.task;

import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BridgeTaskMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ProjectRepository projectRepository;

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Test
    void mapsDtoToTaskWithoutTakingIdFromThePayload() {
        UUID organizationId = UUID.randomUUID();
        Organization organizationRef = new Organization();
        organizationRef.setId(organizationId);
        when(organizationRepository.getReferenceById(organizationId)).thenReturn(organizationRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setOrganizationId(organizationId.toString());
        dto.setTitle("Fix flaky test");
        dto.setDescription("Investigate intermittent CI failure");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");
        dto.setStatus("IN_REVIEW");
        dto.setUrgency(4);
        dto.setConfidence(0.9);

        BridgeTaskMapper mapper = new BridgeTaskMapper(organizationRepository, projectRepository, clock);
        Task task = mapper.map(dto);

        assertThat(task.getId()).isNull();
        assertThat(task.getOrganization()).isSameAs(organizationRef);
        assertThat(task.getTitle()).isEqualTo("Fix flaky test");
        assertThat(task.getSourceType()).isEqualTo(SourceType.GITHUB_ISSUE);
        assertThat(task.getSourceRef()).isEqualTo("github:owner/repo:issue:42");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ETL);
        assertThat(task.getUrgency()).isEqualTo((short) 4);
        assertThat(task.getConfidence()).isEqualTo(0.9f);
        assertThat(task.isAvailable()).isTrue();
        assertThat(task.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void defaultsStatusToNewWhenAbsent() {
        UUID organizationId = UUID.randomUUID();
        Organization organizationRef = new Organization();
        organizationRef.setId(organizationId);
        when(organizationRepository.getReferenceById(organizationId)).thenReturn(organizationRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setOrganizationId(organizationId.toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");

        BridgeTaskMapper mapper = new BridgeTaskMapper(organizationRepository, projectRepository, clock);
        Task task = mapper.map(dto);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }
}

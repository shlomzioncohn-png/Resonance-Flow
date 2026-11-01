package com.rnd.bridge.crm;

import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrmTaskValidatorTest {

    private static final String VALID_TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final CrmTaskValidator VALIDATOR = new CrmTaskValidator(new TaskValidator());

    private static Task baseTask() {
        return new Task(
                VALID_TENANT_ID, null, "Title", "Description", TaskStatus.NEW, 3, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, RoleContext.SALESPERSON,
                SourceType.CRM_DEAL, "crm:D-1", null, null, null);
    }

    @Test
    void acceptsAWellFormedCrmTask() {
        assertDoesNotThrow(() -> VALIDATOR.validate(baseTask()));
    }

    @Test
    void rejectsANullTenantId() {
        Task task = withTenantId(baseTask(), null);
        ValidationException ex = assertThrows(ValidationException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("organizationId"));
    }

    @Test
    void rejectsABlankTenantId() {
        Task task = withTenantId(baseTask(), "   ");
        ValidationException ex = assertThrows(ValidationException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("organizationId"));
    }

    @Test
    void rejectsATenantIdThatIsNotAUuid() {
        Task task = withTenantId(baseTask(), "local-crm-org");
        ValidationException ex = assertThrows(ValidationException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("organizationId"));
        assertTrue(ex.getMessage().contains("UUID"));
    }

    @Test
    void rejectsABlankSourceRef() {
        Task task = withSourceRef(baseTask(), "   ");
        ValidationException ex = assertThrows(ValidationException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("sourceRef"));
    }

    @Test
    void rejectsANullSourceRef() {
        Task task = withSourceRef(baseTask(), null);
        ValidationException ex = assertThrows(ValidationException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("sourceRef"));
    }

    @Test
    void stillDelegatesToTheSharedTaskValidator() {
        // title is TaskValidator's job, not this class's - proves the delegation actually runs,
        // not just the CRM-only checks layered on top of it.
        Task task = withTitle(baseTask(), null);
        ValidationException ex = assertThrows(ValidationException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void theSharedTaskValidatorOnItsOwnStillAcceptsANullTenantSoGitHubAndJiraAreUnaffected() {
        // GitHubClient and JiraClient share TaskValidator directly and never set
        // Task.organizationId. This is what proves adding the tenant/sourceRef checks to
        // CrmTaskValidator, not to TaskValidator itself, doesn't break them.
        Task task = withTenantId(baseTask(), null);
        assertDoesNotThrow(() -> new TaskValidator().validate(task));
    }

    private static Task withTenantId(Task t, String tenantId) {
        return new Task(tenantId, t.projectId(), t.title(), t.description(), t.status(), t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.effortEstimateMinutes(), t.deadline(), t.completedAt());
    }

    private static Task withSourceRef(Task t, String sourceRef) {
        return new Task(t.organizationId(), t.projectId(), t.title(), t.description(), t.status(), t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), sourceRef,
                t.effortEstimateMinutes(), t.deadline(), t.completedAt());
    }

    private static Task withTitle(Task t, String title) {
        return new Task(t.organizationId(), t.projectId(), title, t.description(), t.status(), t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.effortEstimateMinutes(), t.deadline(), t.completedAt());
    }
}

package com.rnd.inbox.audit;

import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    public void createAuditLog(String action, String entityType, UUID entityId, Tenant tenant,
                               User actor, Map<String, Object> newValues, UUID correlationId) {
        AuditLog audit = new AuditLog();
        audit.setTenant(tenant);
        audit.setActor(actor);
        audit.setAction(action);
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setNewValues(newValues);
        audit.setCorrelationId(correlationId);
        audit.setCreatedAt(Instant.now(clock));
        auditLogRepository.save(audit);
    }

    // For an update: before/after are the same snapshot taken on either side of the change.
    // new_values keeps the full "after" snapshot, old_values holds only the fields whose value
    // changed, with the value they had before. An update that changed nothing in the snapshot
    // writes no row at all - the audit trail records changes, not requests.
    public void createUpdateAuditLog(String action, String entityType, UUID entityId, Tenant tenant,
                                     User actor, Map<String, Object> before, Map<String, Object> after,
                                     UUID correlationId) {
        if (before.equals(after)) {
            return;
        }

        AuditLog audit = new AuditLog();
        audit.setTenant(tenant);
        audit.setActor(actor);
        audit.setAction(action);
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setOldValues(changedOnly(before, after));
        audit.setNewValues(after);
        audit.setCorrelationId(correlationId);
        audit.setCreatedAt(Instant.now(clock));
        auditLogRepository.save(audit);
    }

    private Map<String, Object> changedOnly(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            if (!Objects.equals(entry.getValue(), after.get(entry.getKey()))) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return changed;
    }
}

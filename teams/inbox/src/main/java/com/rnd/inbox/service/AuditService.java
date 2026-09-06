package com.rnd.inbox.service;

import com.rnd.inbox.model.entity.AuditLog;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    public void createAuditLog(String action, String entityType, UUID entityId, Organization organization,
                        Map<String, Object> newValues, UUID correlationId) {
        createAuditLog(action, entityType, entityId, organization, null, newValues, correlationId);
    }

    public void createAuditLog(String action, String entityType, UUID entityId, Organization organization,
                        User actor, Map<String, Object> newValues, UUID correlationId) {
        AuditLog audit = new AuditLog();
        audit.setOrganization(organization);
        audit.setActor(actor);
        audit.setAction(action);
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setNewValues(newValues);
        audit.setCorrelationId(correlationId);
        audit.setCreatedAt(Instant.now(clock));
        auditLogRepository.save(audit);
    }
}
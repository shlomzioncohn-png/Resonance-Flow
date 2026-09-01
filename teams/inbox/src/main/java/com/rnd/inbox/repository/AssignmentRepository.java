package com.rnd.inbox.repository;

import com.rnd.inbox.model.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
}

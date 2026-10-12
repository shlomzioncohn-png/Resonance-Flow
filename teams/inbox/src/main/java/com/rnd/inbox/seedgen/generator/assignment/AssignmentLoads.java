package com.rnd.inbox.seedgen.generator.assignment;

import java.util.List;

/** The two halves of every tenant's {@code assignments} row distribution (design doc §4). */
public record AssignmentLoads(List<UserAssignmentLoad> userLoads, List<TeamAssignmentLoad> teamLoads) {
}

// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// Expected finding: Major — exception handling doesn't match the standard (CLAUDE.md §9).
package com.rnd.inbox.service;

import com.rnd.inbox.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskExportService {

    private final TaskRepository taskRepository;

    public void exportTask(Long id) {
        try {
            doExport(id);
        } catch (Exception e) {
            // swallowed — caller has no idea the export failed, nothing is logged either
        }
    }

    private void doExport(Long id) {
        throw new UnsupportedOperationException("not implemented");
    }
}

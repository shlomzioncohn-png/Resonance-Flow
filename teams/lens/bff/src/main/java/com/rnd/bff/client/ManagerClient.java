package com.rnd.bff.client;

import com.rnd.bff.dto.WorkloadEntryDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manager Dashboard's team-workload breakdown. No Inbox/Chain-backed workload
 * source exists yet (see frontend's managerApi.ts), so this returns an empty
 * list. The all-projects grid is real now: see ManagerKpisService.getProjects.
 *
 * TODO: replace with a real call once a workload source exists.
 */
@Service
public class ManagerClient {

    public List<WorkloadEntryDTO> getWorkload() {
        return List.of();
    }
}

package com.rnd.bridge.intake.controller;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.lane.CursorStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bridge/intake")
public class UnstructuredIntakeController {

    // Single-tenant today, like every other default in this lane (WhatsAppImportProperties,
    // SlackApiProperties, ScopingProperties) - revisit if/when this lane serves more than one
    // tenant.
    private static final String TENANT_ID = "kamatech";

    private final Map<RawSourceType, UnstructuredIntakeLane> lanesBySource;
    private final CursorStore cursorStore;

    public UnstructuredIntakeController(List<UnstructuredIntakeLane> lanes, CursorStore cursorStore) {
        this.lanesBySource = lanes.stream()
                .collect(Collectors.toMap(UnstructuredIntakeLane::sourceType, Function.identity()));
        this.cursorStore = cursorStore;
    }

    @PostMapping("/{sourceType}/run")
    public IntakeResult run(@PathVariable RawSourceType sourceType) {
        UnstructuredIntakeLane lane = lanesBySource.get(sourceType);
        if (lane == null) {
            throw new IllegalArgumentException(sourceType + " is not handled by this lane");
        }
        ReadCursor cursor = cursorStore.load(sourceType, TENANT_ID);
        Instant asOf = Instant.now();
        IntakeResult result = lane.run(cursor, asOf);
        // Only reached if run() returned normally (no uncaught exception) - see plan.md "Cursor
        // persistence": update the cursor only after the run has actually gone through, never
        // before. A few rejected records within an otherwise-successful run still advance the
        // cursor; RawObjectEvent.dedupKey() makes any later reprocessing harmless.
        cursorStore.save(sourceType, TENANT_ID, new ReadCursor(asOf));
        return result;
    }
}

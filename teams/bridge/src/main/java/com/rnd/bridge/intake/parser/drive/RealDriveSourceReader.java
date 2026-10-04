package com.rnd.bridge.intake.parser.drive;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the real Google Drive API reader (files.list/changes.list, page tokens for
 * incremental sync — see plan.md). Not implemented yet; enabling bridge.intake.drive.mode=real
 * activates this bean but every call fails until it's filled in.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.drive", name = "mode", havingValue = "real")
public class RealDriveSourceReader implements DriveSourceReader {

    @Override
    public List<SourceRecord> read() {
        throw new UnsupportedOperationException(
                "Real Drive reading is not implemented yet - see plan.md's rollout order");
    }
}

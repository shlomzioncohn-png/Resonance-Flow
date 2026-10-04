package com.rnd.bridge.intake.lane;

import com.rnd.bridge.intake.parser.SourceRecordLoader;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link UnstructuredIntakeLane} per {@link RawSourceType}, excluding MEETING (that's the
 * meeting/doc/audio lane's job, not this one) — mapper/validator/publisher are shared across
 * all of them; only the source reader differs per lane.
 */
@Configuration
class UnstructuredIntakeLaneConfig {

    @Bean
    List<UnstructuredIntakeLane> unstructuredIntakeLanes(
            SourceRecordLoader loader,
            AllowlistScopingFilter scopingFilter,
            ObjectKeyBuilder objectKeyBuilder,
            IntakeLandingService landingService,
            RawObjectValidator validator,
            KafkaRawObjectPublisher publisher,
            GroupStateStore groupStateStore,
            CalendarEventStateStore calendarEventStateStore) {
        return Arrays.stream(RawSourceType.values())
                .filter(sourceType -> sourceType != RawSourceType.MEETING)
                .map(sourceType -> new UnstructuredIntakeLane(
                        sourceType,
                        new SourceRecordReaderAdapter(sourceType, loader),
                        new UnstructuredPayloadMapper(scopingFilter, objectKeyBuilder, landingService),
                        validator,
                        publisher,
                        landingService,
                        groupStateStore,
                        calendarEventStateStore))
                .toList();
    }
}

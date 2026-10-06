package com.rnd.inbox.scheduling;

import com.rnd.inbox.event.SchedulingUpdatedEvent;
import org.springframework.stereotype.Component;

@Component
public class SchedulingProtoMapper {

    public SchedulingEventDto toDto(SchedulingUpdatedEvent proto) {
        SchedulingEventDto dto = new SchedulingEventDto();
        dto.setEventId(proto.hasEventId() ? proto.getEventId() : null);
        dto.setTaskId(proto.getTaskId());
        dto.setAvailable(proto.hasAvailable() ? proto.getAvailable() : null);
        dto.setPriorityScore(proto.hasPriorityScore() ? proto.getPriorityScore() : null);
        return dto;
    }
}

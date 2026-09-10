package com.rnd.inbox.shared.event;

import com.google.protobuf.Message;

import java.util.Map;

public interface ProtoEventMapper<T extends Message> {

    EventType supportedType();

    T toProto(Map<String, Object> payload);
}

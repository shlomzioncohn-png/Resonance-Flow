package com.rnd.bridge.core;

import java.util.stream.Stream;

public interface SourceReader<S> {

    Stream<S> read(ReadCursor cursor);
}

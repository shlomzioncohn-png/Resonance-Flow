package com.rnd.bridge.crm;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.repository.DealRepository;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

@Component
public class CrmSourceReader implements SourceReader<Deal> {

    private final DealRepository dealRepository;

    public CrmSourceReader(DealRepository dealRepository) {
        this.dealRepository = dealRepository;
    }

    @Override
    public Stream<Deal> read(ReadCursor cursor) {
        // cursor is accepted but ignored: only full sync exists today - the
        // synthetic feed has no since-timestamp query to run an incremental
        // read against. Wiring the parameter through now means adding real
        // incremental support later doesn't change this method's signature.
        return dealRepository.findAll().stream();
    }
}

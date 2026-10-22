package com.rnd.inbox;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "inbox.scheduling.enabled=false")
public abstract class AbstractIntegrationTest {
}
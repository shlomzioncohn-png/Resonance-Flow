package com.rnd.chain.service;

import org.springframework.stereotype.Component;

/**
 * Single in-process lock shared by every service that touches dependency edges or reads a DONE
 * status to decide whether an edge counts against blockingCount. Per-task field updates don't need
 * it (TaskRepository#update is an atomic read-modify-write); cycle detection and edge-vs-status
 * decisions do. See CLAUDE.md "Open items".
 */
@Component
public class DependencyGraphLock {
}

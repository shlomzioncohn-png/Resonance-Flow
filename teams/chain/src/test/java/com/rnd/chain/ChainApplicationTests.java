package com.rnd.chain;

import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@Tag("postgres")
@SpringBootTest
class ChainApplicationTests {

    @MockBean
    private TaskRepository taskRepository;

    @MockBean
    private TaskDependencyRepository taskDependencyRepository;

    @MockBean
    private ConstraintRepository constraintRepository;

    @MockBean
    private ConstraintHistoryRepository constraintHistoryRepository;

    @Test
    void contextLoads() {
    }
}
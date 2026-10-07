package com.rnd.inbox.taskselection;

import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskSelectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T09:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    private final TaskAuthorization taskAuthorization = new TaskAuthorization();
    private final TaskSelectionProperties properties = new TaskSelectionProperties();
    private final EffortEstimateResolver effortEstimateResolver = new EffortEstimateResolver(properties);
    private final SelectionScoreCalculator selectionScoreCalculator =
            new SelectionScoreCalculator(properties, effortEstimateResolver);
    private final TaskSelectionKnapsackSolver knapsackSolver = new TaskSelectionKnapsackSolver(effortEstimateResolver);
    private final TaskSelectionMapper mapper = new TaskSelectionMapper(effortEstimateResolver);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private TaskSelectionService service;

    @BeforeEach
    void setUp() {
        service = new TaskSelectionService(taskRepository, userRepository, taskAuthorization,
                effortEstimateResolver, selectionScoreCalculator, knapsackSolver, mapper, clock);
    }

    private void givenCandidates(Task... tasks) {
        when(taskRepository.findCandidateTasksForUser(TENANT_ID, USER_ID)).thenReturn(List.of(tasks));
    }

    private TaskSelectionResponseDto selectTasks(int availableMinutes) {
        return service.selectTasks(TENANT_ID, USER_ID, availableMinutes);
    }

    private static Task task(String title) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setStatus(TaskStatus.APPROVED);
        task.setAvailable(true);
        task.setCreatedAt(NOW);
        task.setPriorityScore(5f);
        return task;
    }

    private static List<String> titles(TaskSelectionResponseDto response) {
        return response.getSelectedTasks().stream().map(SelectedTaskDto::getTitle).toList();
    }

    // ---- A — בחירה בסיסית ----

    @Test
    void selectTasksForWindow_singleAvailableTaskExactlyFillsWindow_returnsThatTask() {
        Task t1 = task("T1");
        t1.setPriorityScore(5f);
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1);

        TaskSelectionResponseDto result = selectTasks(15);

        assertThat(titles(result)).containsExactly("T1");
    }

    @Test
    void selectTasksForWindow_comboOfSmallerTasksOutscoresSingleHighPriorityTask_returnsCombo() {
        Task t1 = task("T1");
        t1.setPriorityScore(10f);
        t1.setEffortEstimateMinutes(30);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(6f);
        t2.setEffortEstimateMinutes(15);
        t2.setEffortEstimateConfidence(0.9f);

        Task t3 = task("T3");
        t3.setPriorityScore(6f);
        t3.setEffortEstimateMinutes(15);
        t3.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2, t3);

        TaskSelectionResponseDto result = selectTasks(30);

        assertThat(titles(result)).containsExactlyInAnyOrder("T2", "T3");
    }

    // ---- B — משימה ארוכה מהחלון ----

    @Test
    void selectTasksForWindow_taskLongerThanWindow_excludedDespiteHighPriorityAndUrgentDeadline() {
        Task t1 = task("T1");
        t1.setPriorityScore(10f);
        t1.setDeadline(NOW.plus(Duration.ofHours(1)));
        t1.setEffortEstimateMinutes(90);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(2f);
        t2.setEffortEstimateMinutes(10);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(20);

        assertThat(titles(result)).containsExactly("T2");
    }

    // ---- C — אין אף משימה מתאימה ----

    static Stream<Arguments> noEligibleCandidatesVariants() {
        Task doneTask = task("T1");
        doneTask.setStatus(TaskStatus.DONE);
        doneTask.setEffortEstimateMinutes(10);

        Task unavailableTask = task("T2");
        unavailableTask.setAvailable(false);
        unavailableTask.setEffortEstimateMinutes(10);

        return Stream.of(
                Arguments.of("emptyTaskList", List.of(), 30),
                Arguments.of("allTasksDoneOrUnavailable", List.of(doneTask, unavailableTask), 30)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noEligibleCandidatesVariants")
    void selectTasksForWindow_noEligibleCandidates_returnsNoSelectionNoFallback(
            String variantName, List<Task> candidateTasks, int availableMinutes) {
        givenCandidates(candidateTasks.toArray(new Task[0]));

        TaskSelectionResponseDto result = selectTasks(availableMinutes);

        assertThat(result.getSelectedTasks()).isEmpty();
        assertThat(result.isUsedFallback()).isFalse();
    }

    @ParameterizedTest(name = "availableMinutes={0}")
    @ValueSource(ints = {0, -5})
    void selectTasksForWindow_nonPositiveBudget_returnsNoSelectionWithoutQueryingTasks(int availableMinutes) {
        TaskSelectionResponseDto result = selectTasks(availableMinutes);

        assertThat(result.getSelectedTasks()).isEmpty();
        assertThat(result.isUsedFallback()).isFalse();
        verifyNoInteractions(taskRepository);
    }

    // ---- D — התחשבות ב-Priority ----

    @Test
    void selectTasksForWindow_equalDeadlineAndTimeFit_higherPriorityWins() {
        Task t1 = task("T1");
        t1.setPriorityScore(8f);
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(3f);
        t2.setEffortEstimateMinutes(15);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(20);

        assertThat(titles(result)).containsExactly("T1");
    }

    // ---- E — התחשבות ב-Deadline / Urgency ----

    @Test
    void selectTasksForWindow_closerDeadlineBeatsFarDeadline_allElseEqual() {
        Task t1 = task("T1");
        t1.setPriorityScore(5f);
        t1.setDeadline(NOW.plus(Duration.ofHours(1)));
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(5f);
        t2.setDeadline(NOW.plus(Duration.ofDays(30)));
        t2.setEffortEstimateMinutes(15);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(15);

        assertThat(titles(result)).containsExactly("T1");
    }

    static Stream<Arguments> overdueBonusBoundaryVariants() {
        return Stream.of(
                Arguments.of("capNeverOutweighsPriorityGap", 8.0, null, 2.0, Duration.ofDays(30).negated(), "T1"),
                Arguments.of("moreOverdueOutscoresLessOverdue", 2.0, Duration.ofHours(1).negated(), 2.0,
                        Duration.ofDays(30).negated(), "T2")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overdueBonusBoundaryVariants")
    void selectTasksForWindow_overdueBonusBoundaryBehavior_capNeverInvertsPriorityStillDistinguishesMagnitude(
            String variantName, double t1Priority, Duration t1DeadlineOffset, double t2Priority,
            Duration t2DeadlineOffset, String expectedWinnerTitle) {
        Task t1 = task("T1");
        t1.setPriorityScore((float) t1Priority);
        if (t1DeadlineOffset != null) {
            t1.setDeadline(NOW.plus(t1DeadlineOffset));
        }
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore((float) t2Priority);
        if (t2DeadlineOffset != null) {
            t2.setDeadline(NOW.plus(t2DeadlineOffset));
        }
        t2.setEffortEstimateMinutes(15);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(15);

        assertThat(titles(result)).containsExactly(expectedWinnerTitle);
    }

    // ---- F — משימות חסומות (Dependencies) ----

    @Test
    void selectTasksForWindow_taskBlockedByDependency_excludedRegardlessOfOtherAttributes() {
        Task t1 = task("T1");
        t1.setPriorityScore(10f);
        t1.setDeadline(NOW.plus(Duration.ofHours(1)));
        t1.setEffortEstimateMinutes(10);
        t1.setAvailable(false);

        Task t2 = task("T2");
        t2.setPriorityScore(1f);
        t2.setEffortEstimateMinutes(10);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(10);

        assertThat(titles(result)).containsExactly("T2");
    }

    // ---- G — Status וזמינות לביצוע ----

    @Test
    void selectTasksForWindow_statusEligibility_terminalStatusesAndSnoozeGate() {
        Task t1 = task("T1");
        t1.setStatus(TaskStatus.DONE);
        t1.setEffortEstimateMinutes(10);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setStatus(TaskStatus.CANCELLED);
        t2.setEffortEstimateMinutes(10);
        t2.setEffortEstimateConfidence(0.9f);

        Task t3 = task("T3");
        t3.setStatus(TaskStatus.REJECTED);
        t3.setEffortEstimateMinutes(10);
        t3.setEffortEstimateConfidence(0.9f);

        Task t4 = task("T4");
        t4.setStatus(TaskStatus.APPROVED);
        t4.setEffortEstimateMinutes(10);
        t4.setEffortEstimateConfidence(0.9f);

        Task t5 = task("T5");
        t5.setStatus(TaskStatus.SNOOZED);
        t5.setSnoozedUntil(NOW.plus(Duration.ofDays(3)));
        t5.setEffortEstimateMinutes(10);
        t5.setEffortEstimateConfidence(0.9f);

        Task t6 = task("T6");
        t6.setStatus(TaskStatus.SNOOZED);
        t6.setSnoozedUntil(NOW.minus(Duration.ofDays(1)));
        t6.setEffortEstimateMinutes(10);
        t6.setEffortEstimateConfidence(0.9f);

        Task t7 = task("T7");
        t7.setStatus(TaskStatus.SNOOZED);
        t7.setSnoozedUntil(null);
        t7.setEffortEstimateMinutes(10);
        t7.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2, t3, t4, t5, t6, t7);

        TaskSelectionResponseDto result = selectTasks(20);

        assertThat(titles(result)).containsExactlyInAnyOrder("T4", "T6");
    }

    // ---- H — מקרי קצה של זמן + Fallback ----

    @Test
    void selectTasksForWindow_noTaskFitsShortWindow_triggersSingleFallbackRound() {
        Task t1 = task("T1");
        t1.setPriorityScore(5f);
        t1.setEffortEstimateMinutes(45);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(5f);
        t2.setEffortEstimateMinutes(60);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(5);

        assertThat(titles(result)).containsExactly("T1");
        assertThat(result.isUsedFallback()).isTrue();
    }

    @Test
    void selectTasksForWindow_windowMuchLargerThanAllTasks_selectsAllPositiveScoreCandidates() {
        Task t1 = task("T1");
        t1.setPriorityScore(8f);
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(2f);
        t2.setEffortEstimateMinutes(10);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(180);

        assertThat(titles(result)).containsExactlyInAnyOrder("T1", "T2");
        assertThat(result.getRemainingMinutes()).isEqualTo(155);
    }

    // ---- I — שבירת שוויון (Tie-breaking) ----

    @Test
    void selectTasksForWindow_equalScore_tieBreakByMoreMinutesUsed() {
        Task t1 = task("T1");
        t1.setPriorityScore(6f);
        t1.setDeadline(NOW.plus(Duration.ofDays(2)));
        t1.setEffortEstimateMinutes(20);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(6f);
        t2.setDeadline(NOW.plus(Duration.ofDays(2)));
        t2.setEffortEstimateMinutes(10);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        TaskSelectionResponseDto result = selectTasks(25);

        assertThat(titles(result)).containsExactly("T1");
    }

    @Test
    void selectTasksForWindow_fullTie_tieBreakByTaskIdIsDeterministic() {
        Task t1 = task("T1");
        t1.setPriorityScore(5f);
        t1.setDeadline(NOW.plus(Duration.ofDays(1)));
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        Task t2 = task("T2");
        t2.setPriorityScore(5f);
        t2.setDeadline(NOW.plus(Duration.ofDays(1)));
        t2.setEffortEstimateMinutes(15);
        t2.setEffortEstimateConfidence(0.9f);

        givenCandidates(t1, t2);

        List<String> firstRun = titles(selectTasks(15));
        List<String> secondRun = titles(selectTasks(15));

        assertThat(firstRun).hasSize(1);
        assertThat(firstRun).isEqualTo(secondRun);
    }

    // ---- J — דטרמיניזם ----

    static Stream<Arguments> determinismVariants() {
        Task alpha = task("Alpha");
        alpha.setPriorityScore(7f);
        alpha.setEffortEstimateMinutes(20);
        alpha.setEffortEstimateConfidence(0.8f);

        Task beta = task("Beta");
        beta.setPriorityScore(4f);
        beta.setEffortEstimateMinutes(15);
        beta.setEffortEstimateConfidence(0.9f);

        Task gamma = task("Gamma");
        gamma.setPriorityScore(9f);
        gamma.setEffortEstimateMinutes(10);
        gamma.setEffortEstimateConfidence(0.6f);

        List<Task> original = List.of(alpha, beta, gamma);
        List<Task> sameOrder = List.of(alpha, beta, gamma);
        List<Task> shuffled = List.of(gamma, alpha, beta);

        return Stream.of(
                Arguments.of("sameInputTwice", original, sameOrder),
                Arguments.of("inputOrderShuffled", original, shuffled)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("determinismVariants")
    void selectTasksForWindow_isDeterministicAndOrderInvariant(
            String variantName, List<Task> firstOrder, List<Task> secondOrder) {
        when(taskRepository.findCandidateTasksForUser(TENANT_ID, USER_ID)).thenReturn(firstOrder);
        TaskSelectionResponseDto first = selectTasks(30);

        when(taskRepository.findCandidateTasksForUser(TENANT_ID, USER_ID)).thenReturn(secondOrder);
        TaskSelectionResponseDto second = selectTasks(30);

        assertThat(titles(first)).isEqualTo(titles(second));
        assertThat(first.getTotalValue()).isEqualTo(second.getTotalValue());
        assertThat(first.getRemainingMinutes()).isEqualTo(second.getRemainingMinutes());
    }

    // ---- K — ברירות מחדל + Aging ----

    @Test
    void selectTasksForWindow_missingEffortEstimate_usesDefaultAndRemainsCandidate() {
        Task t1 = task("T1");
        t1.setPriorityScore(5f);
        // effortEstimateMinutes and effortEstimateConfidence intentionally left unset

        assertThat(effortEstimateResolver.resolvedEffortEstimateMinutes(t1)).isEqualTo(30);
        assertThat(effortEstimateResolver.resolvedEffortEstimateConfidence(t1)).isEqualTo(0.1);
        assertThat(selectionScoreCalculator.normalizedTimeFit(t1)).isEqualTo(0.1);

        givenCandidates(t1);

        TaskSelectionResponseDto result = selectTasks(30);

        assertThat(titles(result)).containsExactly("T1");
        assertThat(result.getSelectedTasks().get(0).getEffortEstimateMinutes()).isEqualTo(30);
    }

    @Test
    void selectTasksForWindow_missingPriorityScore_usesDefaultAndRemainsCandidate() {
        Task t1 = task("T1");
        t1.setPriorityScore(null);
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);

        assertThat(selectionScoreCalculator.normalizedPriority(t1)).isEqualTo(0.5);

        givenCandidates(t1);

        TaskSelectionResponseDto result = selectTasks(15);

        assertThat(titles(result)).containsExactly("T1");
    }

    @Test
    void selectTasksForWindow_missingPriorityScore_ranksBetweenLowerAndHigherScoredTasks() {
        Task high = task("High");
        high.setPriorityScore(8f);
        Task unscored = task("Unscored");
        unscored.setPriorityScore(null);
        Task low = task("Low");
        low.setPriorityScore(2f);
        for (Task candidate : List.of(high, unscored, low)) {
            candidate.setEffortEstimateMinutes(15);
            candidate.setEffortEstimateConfidence(0.9f);
        }

        givenCandidates(low, unscored, high);

        assertThat(titles(selectTasks(20))).containsExactly("High");
        assertThat(titles(selectTasks(30))).containsExactlyInAnyOrder("High", "Unscored");
    }

    @Test
    void selectTasksForWindow_missingPriorityScoreWithDeadline_doesNotFail() {
        Task t1 = task("T1");
        t1.setPriorityScore(null);
        t1.setEffortEstimateMinutes(15);
        t1.setEffortEstimateConfidence(0.9f);
        t1.setDeadline(NOW.plus(Duration.ofHours(6)));
        Task overdue = task("Overdue");
        overdue.setPriorityScore(null);
        overdue.setEffortEstimateMinutes(15);
        overdue.setEffortEstimateConfidence(0.9f);
        overdue.setDeadline(NOW.minus(Duration.ofDays(2)));

        givenCandidates(t1, overdue);

        assertThat(titles(selectTasks(30))).containsExactlyInAnyOrder("T1", "Overdue");
    }

    @Test
    void selectTasksForWindow_effortPresentButConfidenceMissing_confidenceDefaultsToFull() {
        Task t1 = task("T1");
        t1.setPriorityScore(5f);
        t1.setEffortEstimateMinutes(20);
        // effortEstimateConfidence intentionally left unset

        assertThat(effortEstimateResolver.resolvedEffortEstimateConfidence(t1)).isEqualTo(1.0);
        assertThat(selectionScoreCalculator.normalizedTimeFit(t1)).isEqualTo(1.0);

        givenCandidates(t1);

        TaskSelectionResponseDto result = selectTasks(20);

        assertThat(titles(result)).containsExactly("T1");
    }

    @Test
    void selectTasksForWindow_agingBoostDistinguishesByWaitTime() {
        // Variant: outranksBrandNewTask
        Task old = task("Old");
        old.setPriorityScore(2f);
        old.setEffortEstimateMinutes(15);
        old.setEffortEstimateConfidence(0.9f);
        old.setCreatedAt(NOW.minus(Duration.ofDays(180)));

        Task brandNew = task("BrandNew");
        brandNew.setPriorityScore(2f);
        brandNew.setEffortEstimateMinutes(15);
        brandNew.setEffortEstimateConfidence(0.9f);
        brandNew.setCreatedAt(NOW);

        givenCandidates(old, brandNew);

        TaskSelectionResponseDto outranksBrandNewTaskResult = selectTasks(15);

        assertThat(titles(outranksBrandNewTaskResult)).containsExactly("Old");

        // Variant: stillDistinguishesTwoOldTasks
        Task monthOld = task("MonthOld");
        monthOld.setPriorityScore(2f);
        monthOld.setEffortEstimateMinutes(15);
        monthOld.setEffortEstimateConfidence(0.9f);
        monthOld.setCreatedAt(NOW.minus(Duration.ofDays(30)));

        Task yearOld = task("YearOld");
        yearOld.setPriorityScore(2f);
        yearOld.setEffortEstimateMinutes(15);
        yearOld.setEffortEstimateConfidence(0.9f);
        yearOld.setCreatedAt(NOW.minus(Duration.ofDays(365)));

        givenCandidates(monthOld, yearOld);

        TaskSelectionResponseDto stillDistinguishesTwoOldTasksResult = selectTasks(15);

        assertThat(titles(stillDistinguishesTwoOldTasksResult)).containsExactly("YearOld");
    }

    // ---- selectTasksForUser — who the selection is for, and in which tenant ----

    private static User user(UserRole role, UUID tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        user.setRole(role);
        return user;
    }

    private static Task fittingTask(String title) {
        Task task = task(title);
        task.setEffortEstimateMinutes(15);
        task.setEffortEstimateConfidence(0.9f);
        return task;
    }

    @Test
    void selectTasksForUser_whenActorUnknown_throwsUnauthenticated() {
        UUID unknownActorId = UUID.randomUUID();
        when(userRepository.findById(unknownActorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectTasksForUser(unknownActorId, null, 30))
                .isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void selectTasksForUser_withoutUserId_selectsActorsOwnTasksInActorsTenant() {
        User employee = user(UserRole.EMPLOYEE, TENANT_ID);
        when(userRepository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(taskRepository.findCandidateTasksForUser(TENANT_ID, employee.getId()))
                .thenReturn(List.of(fittingTask("Mine")));

        TaskSelectionResponseDto result = service.selectTasksForUser(employee.getId(), null, 30);

        assertThat(titles(result)).containsExactly("Mine");
    }

    @Test
    void selectTasksForUser_whenNonAdminPassesUserId_selectsThatUsersTasksWithinActorsTenant() {
        User employee = user(UserRole.EMPLOYEE, TENANT_ID);
        UUID colleagueId = UUID.randomUUID();
        when(userRepository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(taskRepository.findCandidateTasksForUser(TENANT_ID, colleagueId))
                .thenReturn(List.of(fittingTask("Colleague's")));

        TaskSelectionResponseDto result = service.selectTasksForUser(employee.getId(), colleagueId, 30);

        assertThat(titles(result)).containsExactly("Colleague's");
    }

    @Test
    void selectTasksForUser_whenSystemAdminPassesUserIdFromAnotherTenant_selectsInThatUsersTenant() {
        UUID otherTenantId = UUID.randomUUID();
        User admin = user(UserRole.SYSTEM_ADMIN, TENANT_ID);
        User target = user(UserRole.EMPLOYEE, otherTenantId);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(taskRepository.findCandidateTasksForUser(otherTenantId, target.getId()))
                .thenReturn(List.of(fittingTask("Other tenant's")));

        TaskSelectionResponseDto result = service.selectTasksForUser(admin.getId(), target.getId(), 30);

        assertThat(titles(result)).containsExactly("Other tenant's");
    }

    @Test
    void selectTasksForUser_whenSystemAdminPassesUnknownUserId_returnsEmptySelection() {
        User admin = user(UserRole.SYSTEM_ADMIN, TENANT_ID);
        UUID unknownUserId = UUID.randomUUID();
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());

        TaskSelectionResponseDto result = service.selectTasksForUser(admin.getId(), unknownUserId, 30);

        assertThat(result.getSelectedTasks()).isEmpty();
        assertThat(result.getRemainingMinutes()).isEqualTo(30);
        verifyNoInteractions(taskRepository);
    }
}

package com.rnd.inbox.shared.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLogRequestInterceptorTest {

    private final SqlLogRequestInterceptor interceptor = new SqlLogRequestInterceptor();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ---- request id ----

    @Test
    void preHandle_withoutRequestIdHeader_generatesOne() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).matches("req-[0-9a-f]{8}");
    }

    @Test
    void preHandle_twoRequests_getDifferentIds() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));
        String first = MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY);

        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("listTasks"));

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isNotEqualTo(first);
    }

    @Test
    void preHandle_withRequestIdHeader_usesTheCallersId() throws Exception {
        request.addHeader(SqlLogRequestInterceptor.REQUEST_ID_HEADER, "client-42.a_b");

        interceptor.preHandle(request, response, handler("listTasks"));

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isEqualTo("client-42.a_b");
    }

    @Test
    void preHandle_withUnacceptableRequestIdHeader_generatesOneInstead() throws Exception {
        for (String unacceptable : new String[]{"has space", "line\nbreak", "quote\"d", "", "x".repeat(65)}) {
            MockHttpServletRequest withBadId = new MockHttpServletRequest();
            withBadId.addHeader(SqlLogRequestInterceptor.REQUEST_ID_HEADER, unacceptable);

            interceptor.preHandle(withBadId, new MockHttpServletResponse(), handler("listTasks"));

            assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).matches("req-[0-9a-f]{8}");
        }
    }

    @Test
    void preHandle_requestIdOfMaximumLength_isAccepted() throws Exception {
        request.addHeader(SqlLogRequestInterceptor.REQUEST_ID_HEADER, "x".repeat(64));

        interceptor.preHandle(request, response, handler("listTasks"));

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isEqualTo("x".repeat(64));
    }

    @Test
    void preHandle_returnsTheRequestIdInTheResponseHeader() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));

        assertThat(response.getHeader(SqlLogRequestInterceptor.REQUEST_ID_HEADER))
                .isEqualTo(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY));
    }

    @Test
    void preHandle_calledAgainForTheSameRequest_keepsTheSameId() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));
        String first = MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY);
        interceptor.afterCompletion(request, response, handler("listTasks"), null);

        interceptor.preHandle(request, response, handler("listTasks"));

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isEqualTo(first);
    }

    // ---- operation ----

    @Test
    void preHandle_namesTheOperationAfterTheControllerMethod() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));

        assertThat(MDC.get(SqlLogRequestInterceptor.OPERATION_KEY)).isEqualTo("LIST_TASKS");
    }

    @Test
    void preHandle_methodNameOfSeveralWords_isSplitAtEveryWord() throws Exception {
        interceptor.preHandle(request, response, handler("getTaskById"));

        assertThat(MDC.get(SqlLogRequestInterceptor.OPERATION_KEY)).isEqualTo("GET_TASK_BY_ID");
    }

    @Test
    void preHandle_handlerThatIsNotAControllerMethod_setsNoOperation() throws Exception {
        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(MDC.get(SqlLogRequestInterceptor.OPERATION_KEY)).isNull();
        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isNotNull();
    }

    @Test
    void preHandle_alwaysLetsTheRequestProceed() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("listTasks"))).isTrue();
    }

    // ---- cleanup ----

    @Test
    void afterCompletion_clearsBothValues() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));

        interceptor.afterCompletion(request, response, handler("listTasks"), null);

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isNull();
        assertThat(MDC.get(SqlLogRequestInterceptor.OPERATION_KEY)).isNull();
    }

    @Test
    void afterCompletion_whenTheHandlerFailed_stillClears() throws Exception {
        interceptor.preHandle(request, response, handler("listTasks"));

        interceptor.afterCompletion(request, response, handler("listTasks"), new IllegalStateException("boom"));

        assertThat(MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY)).isNull();
    }

    @Test
    void afterCompletion_leavesOtherMdcEntriesAlone() throws Exception {
        MDC.put("unrelated", "kept");
        interceptor.preHandle(request, response, handler("listTasks"));

        interceptor.afterCompletion(request, response, handler("listTasks"), null);

        assertThat(MDC.get("unrelated")).isEqualTo("kept");
    }

    // ---- helpers ----

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod(methodName));
    }

    static class SampleController {

        public void listTasks() {
        }

        public void getTaskById() {
        }
    }
}

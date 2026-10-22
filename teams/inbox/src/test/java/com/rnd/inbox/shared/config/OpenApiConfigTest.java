package com.rnd.inbox.shared.config;

import com.rnd.inbox.shared.security.CurrentActor;
import com.rnd.inbox.shared.security.RequireRole;
import com.rnd.inbox.user.model.UserRole;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OperationCustomizer actorHeaderCustomizer = new OpenApiConfig().actorHeaderCustomizer();

    @Test
    void actorHeaderCustomizer_whenHandlerTakesCurrentActor_addsRequiredActorHeader() throws NoSuchMethodException {
        Operation operation = actorHeaderCustomizer.customize(new Operation(), handler("withCurrentActor", UUID.class));

        assertThat(operation.getParameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.getName()).isEqualTo("X-Actor-Id");
            assertThat(parameter.getIn()).isEqualTo("header");
            assertThat(parameter.getRequired()).isTrue();
            assertThat(parameter.getSchema().getFormat()).isEqualTo("uuid");
        });
    }

    @Test
    void actorHeaderCustomizer_whenHandlerRequiresRole_addsActorHeader() throws NoSuchMethodException {
        Operation operation = actorHeaderCustomizer.customize(new Operation(), handler("withRequiredRole"));

        assertThat(operation.getParameters()).extracting(Parameter::getName).containsExactly("X-Actor-Id");
    }

    @Test
    void actorHeaderCustomizer_whenHandlerNeedsNoActor_addsNoParameter() throws NoSuchMethodException {
        Operation operation = actorHeaderCustomizer.customize(new Operation(), handler("withoutActor"));

        assertThat(operation.getParameters()).isNullOrEmpty();
    }

    private static HandlerMethod handler(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod(methodName, parameterTypes));
    }

    static class SampleController {

        public void withCurrentActor(@CurrentActor UUID actorId) {
        }

        @RequireRole(UserRole.TEAM_LEAD)
        public void withRequiredRole() {
        }

        public void withoutActor() {
        }
    }
}

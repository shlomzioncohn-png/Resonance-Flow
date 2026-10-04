package com.rnd.inbox.shared.security;

import com.rnd.inbox.shared.exception.UnauthenticatedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentActorArgumentResolverTest {

    private final CurrentActorArgumentResolver resolver = new CurrentActorArgumentResolver();

    @Mock
    private NativeWebRequest webRequest;

    @Test
    void supportsParameter_returnsTrueForCurrentActorAnnotatedUuidParameter() throws NoSuchMethodException {
        assertThat(resolver.supportsParameter(annotatedUuidParameter())).isTrue();
    }

    @Test
    void supportsParameter_returnsFalseForParameterWithoutTheAnnotation() throws NoSuchMethodException {
        assertThat(resolver.supportsParameter(plainUuidParameter())).isFalse();
    }

    @Test
    void resolveArgument_withValidHeader_returnsParsedUuid() throws NoSuchMethodException {
        UUID actorId = UUID.randomUUID();
        when(webRequest.getHeader("X-Actor-Id")).thenReturn(actorId.toString());

        Object result = resolver.resolveArgument(annotatedUuidParameter(), null, webRequest, null);

        assertThat(result).isEqualTo(actorId);
    }

    @Test
    void resolveArgument_withMissingHeader_throwsUnauthenticatedException() throws NoSuchMethodException {
        when(webRequest.getHeader("X-Actor-Id")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveArgument(annotatedUuidParameter(), null, webRequest, null))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void resolveArgument_withBlankHeader_throwsUnauthenticatedException() throws NoSuchMethodException {
        when(webRequest.getHeader("X-Actor-Id")).thenReturn("   ");

        assertThatThrownBy(() -> resolver.resolveArgument(annotatedUuidParameter(), null, webRequest, null))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void resolveArgument_withMalformedUuid_throwsUnauthenticatedException() throws NoSuchMethodException {
        when(webRequest.getHeader("X-Actor-Id")).thenReturn("not-a-uuid");

        assertThatThrownBy(() -> resolver.resolveArgument(annotatedUuidParameter(), null, webRequest, null))
                .isInstanceOf(UnauthenticatedException.class);
    }

    private MethodParameter annotatedUuidParameter() throws NoSuchMethodException {
        Method method = Fixture.class.getDeclaredMethod("withCurrentActor", UUID.class);
        return new MethodParameter(method, 0);
    }

    private MethodParameter plainUuidParameter() throws NoSuchMethodException {
        Method method = Fixture.class.getDeclaredMethod("withoutAnnotation", UUID.class);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private static class Fixture {
        void withCurrentActor(@CurrentActor UUID actorId) {
        }

        void withoutAnnotation(UUID actorId) {
        }
    }
}

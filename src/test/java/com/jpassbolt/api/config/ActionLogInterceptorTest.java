package com.jpassbolt.api.config;

import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.ActionLogService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ActionLogInterceptor}'s request-to-fields adapter logic, with a
 * mocked {@link ActionLogService}. Focus: the guards that decide WHEN a request is logged —
 * only the primary {@link DispatcherType#REQUEST} dispatch and only {@link HandlerMethod}
 * handlers — plus correct derivation of action name / context / status.
 */
class ActionLogInterceptorTest {

    private final ActionLogService actionLogService = mock(ActionLogService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ActionLogInterceptor interceptor = new ActionLogInterceptor(actionLogService, userRepository);

    /** A real method to back a {@link HandlerMethod}; its name feeds the derived action name. */
    @SuppressWarnings("unused")
    public void sampleHandler() {
    }

    private HandlerMethod handlerMethod() throws NoSuchMethodException {
        Method m = ActionLogInterceptorTest.class.getMethod("sampleHandler");
        return new HandlerMethod(this, m);
    }

    @Test
    void logsOnPrimaryRequestDispatchWithDerivedFields() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources.json");
        request.setDispatcherType(DispatcherType.REQUEST);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, handlerMethod(), null);

        // No SecurityContext set up -> anonymous -> null user id.
        verify(actionLogService, times(1)).logAction(
                isNull(), eq("ActionLogInterceptorTest.sampleHandler"), eq("GET /resources.json"), eq(200));
    }

    @Test
    void doesNotLogOnErrorDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources.json");
        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        interceptor.afterCompletion(request, response, handlerMethod(), null);

        verify(actionLogService, never()).logAction(any(), any(), any(), anyInt());
    }

    @Test
    void doesNotLogOnForwardDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources.json");
        request.setDispatcherType(DispatcherType.FORWARD);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, handlerMethod(), null);

        verify(actionLogService, never()).logAction(any(), any(), any(), anyInt());
    }

    @Test
    void doesNotLogForNonControllerHandlers() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/static/x.css");
        request.setDispatcherType(DispatcherType.REQUEST);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // A resource handler (not a HandlerMethod) must be ignored.
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(actionLogService, never()).logAction(any(), any(), any(), anyInt());
    }
}

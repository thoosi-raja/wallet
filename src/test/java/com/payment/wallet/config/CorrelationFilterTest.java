package com.payment.wallet.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationFilterTest {
    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void propagatesCorrelationAndClearsMdcEvenWhenRequestFails() {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER, "request-123");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(CorrelationFilter.MDC_KEY)).isEqualTo("request-123");
            throw new ServletException("test failure");
        })).isInstanceOf(ServletException.class);
        assertThat(response.getHeader(CorrelationFilter.HEADER)).isEqualTo("request-123");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void replacesUnsafeInputAndDoesNotLeakCorrelationBetweenRequests() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER, "unsafe\r\nheader");
        var first = new MockHttpServletResponse();
        filter.doFilter(request, first, (req, res) ->
                assertThat(MDC.get(CorrelationFilter.MDC_KEY)).isEqualTo(first.getHeader(CorrelationFilter.HEADER)));
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {});
        assertThat(first.getHeader(CorrelationFilter.HEADER)).matches("[0-9a-f-]{36}");
        assertThat(second.getHeader(CorrelationFilter.HEADER)).isNotEqualTo(first.getHeader(CorrelationFilter.HEADER));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}

package org.ajikhoji.ajiorders.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Should generate a new correlation ID when no header is present in the request")
    void shouldGenerateCorrelationIdWhenMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcCorrelationIdDuringChain = new AtomicReference<>();

        FilterChain filterChain = (req, res) -> {
            mdcCorrelationIdDuringChain.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY));
        };

        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotNull().isNotBlank();
        assertThat(responseHeader).matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
        assertThat(mdcCorrelationIdDuringChain.get()).isEqualTo(responseHeader);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Should propagate existing correlation ID when present in the request header")
    void shouldPropagateExistingCorrelationId() throws ServletException, IOException {
        String existingCorrelationId = "upstream-service-trace-id-12345";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, existingCorrelationId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcCorrelationIdDuringChain = new AtomicReference<>();

        FilterChain filterChain = (req, res) -> {
            mdcCorrelationIdDuringChain.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY));
        };

        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isEqualTo(existingCorrelationId);
        assertThat(mdcCorrelationIdDuringChain.get()).isEqualTo(existingCorrelationId);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Should generate a new correlation ID when header is present but empty or whitespace")
    void shouldGenerateNewCorrelationIdWhenHeaderIsBlank() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcCorrelationIdDuringChain = new AtomicReference<>();

        FilterChain filterChain = (req, res) -> {
            mdcCorrelationIdDuringChain.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY));
        };

        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotNull().isNotBlank();
        assertThat(responseHeader).matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
        assertThat(mdcCorrelationIdDuringChain.get()).isEqualTo(responseHeader);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Should clear MDC even if exception occurs downstream")
    void shouldClearMdcOnDownstreamException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            throw new RuntimeException("Simulated downstream error");
        };

        try {
            filter.doFilter(request, response, filterChain);
        } catch (Exception ignored) {
        }

        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }
}


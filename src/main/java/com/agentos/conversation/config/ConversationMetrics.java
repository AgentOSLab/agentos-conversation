package com.agentos.conversation.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers domain-specific Micrometer metrics for the Conversation service.
 * Tracks sessions, runs, and message throughput.
 * Exposed via {@code /actuator/metrics/agentos.*} and
 * {@code /actuator/prometheus} for the Monitor Service.
 */
@Component
public class ConversationMetrics {

    private final AtomicLong activeSessions = new AtomicLong(0);
    private final AtomicLong activeRuns = new AtomicLong(0);

    private final Counter sessionsCreated;
    private final Counter messagesReceived;
    private final Counter runsCreated;
    private final Counter runsCompleted;
    private final Counter runsFailed;
    private final Timer runDuration;
    private final MeterRegistry registry;

    public ConversationMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Gauges
        registry.gauge("agentos.conversation.sessions.active", activeSessions);
        registry.gauge("agentos.conversation.runs.active", activeRuns);

        // Counters
        sessionsCreated = Counter.builder("agentos.conversation.sessions.created")
                .description("Total sessions created")
                .register(registry);

        messagesReceived = Counter.builder("agentos.conversation.messages.received")
                .description("Total user messages received")
                .register(registry);

        runsCreated = Counter.builder("agentos.conversation.runs.created")
                .description("Total runs created")
                .register(registry);

        runsCompleted = Counter.builder("agentos.conversation.runs.completed")
                .description("Total runs completed successfully")
                .register(registry);

        runsFailed = Counter.builder("agentos.conversation.runs.failed")
                .description("Total runs that failed")
                .register(registry);

        // Timer
        runDuration = Timer.builder("agentos.conversation.run.duration")
                .description("Run execution duration (end-to-end)")
                .register(registry);
    }

    // ── Recording methods ──

    public void sessionCreated() {
        sessionsCreated.increment();
        activeSessions.incrementAndGet();
    }

    public void sessionClosed() {
        activeSessions.decrementAndGet();
    }

    public void messageReceived() {
        messagesReceived.increment();
    }

    public void runStarted() {
        runsCreated.increment();
        activeRuns.incrementAndGet();
    }

    public void runCompleted(java.time.Duration duration) {
        activeRuns.decrementAndGet();
        runsCompleted.increment();
        runDuration.record(duration);
    }

    public void runFailed() {
        activeRuns.decrementAndGet();
        runsFailed.increment();
    }

    public void messageRouted(String routeType) {
        Counter.builder("agentos.conversation.messages.routed")
                .tag("route_type", routeType)
                .register(registry)
                .increment();
    }
}

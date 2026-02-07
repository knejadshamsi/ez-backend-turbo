package ez.backend.turbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);

    private final int maxRequestsPerSecond;
    private final int banDurationSeconds;
    private final ConcurrentHashMap<String, IpState> ipStates = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScheduledExecutorService cleanupExecutor;

    public RateLimitFilter(StartupValidator validator) {
        this.maxRequestsPerSecond = validator.getRateLimitMaxRequests();
        this.banDurationSeconds = validator.getRateLimitBanDuration();
    }

    @PostConstruct
    void startCleanup() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ratelimit-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        IpState state = ipStates.computeIfAbsent(ip, k -> new IpState());

        synchronized (state) {
            if (state.bannedUntil > now) {
                writeRateLimitResponse(response, state.bannedUntil, now);
                return;
            }

            long currentSecond = now / 1000;
            if (currentSecond != state.windowStart) {
                state.windowStart = currentSecond;
                state.count = 1;
            } else {
                state.count++;
            }

            if (state.count > maxRequestsPerSecond) {
                state.bannedUntil = now + (banDurationSeconds * 1000L);
                log.warn("[SYSTEM] {} — IP: {}, {}: {}",
                        L.msg("ratelimit.blocked"),
                        ip,
                        L.msg("ratelimit.ban.duration"),
                        banDurationSeconds);
                writeRateLimitResponse(response, state.bannedUntil, now);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeRateLimitResponse(HttpServletResponse response, long bannedUntil, long now) throws IOException {
        long retryAfter = Math.max(1, (bannedUntil - now + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        Map<String, Object> body = Map.of(
                "statusCode", 429,
                "message", "Too many requests",
                "payload", Map.of(),
                "timestamp", Instant.now().toString()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        long staleThreshold = now - 60_000;
        ipStates.entrySet().removeIf(entry -> {
            IpState state = entry.getValue();
            synchronized (state) {
                return state.bannedUntil <= now && (state.windowStart * 1000) < staleThreshold;
            }
        });
    }

    @PreDestroy
    void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    private static class IpState {
        long windowStart;
        int count;
        long bannedUntil;
    }
}

package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.ApiErrorPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Turns {@code RestClient} outcomes into {@link UpstreamResult}s, the same way for every client.
 *
 * <p>The split is by who said no. A 4xx is the other service answering: it carries the platform's
 * {@code {code, message}} body, and the code is what the agent needs to explain the refusal.
 * Anything else - a 5xx, a refused connection, a timeout, a body that will not parse - is no
 * answer at all.
 *
 * <p>Bodies are parsed by hand with a plain {@link ObjectMapper} rather than through the
 * exception's converters, so the outcome does not depend on how a builder was configured. A 4xx
 * whose body is not the platform's shape still gets a usable code, {@code HTTP_<status>}.
 */
final class UpstreamResults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private UpstreamResults() {
    }

    static <T> UpstreamResult<T> rejected(HttpClientErrorException ex) {
        ApiErrorPayload body = readLeniently(ex.getResponseBodyAsString(), ApiErrorPayload.class);
        if (body != null && body.code() != null && !body.code().isBlank()) {
            return new UpstreamResult.Rejected<>(body.code(), body.message());
        }
        return new UpstreamResult.Rejected<>("HTTP_" + ex.getStatusCode().value(), ex.getStatusText());
    }

    static <T> UpstreamResult<T> unavailable(String service, Exception ex) {
        return new UpstreamResult.Unavailable<>(service + " gave no usable answer: " + ex.getMessage());
    }

    /** {@code json} as {@code type}, or null when it is absent or is not that shape. */
    static <T> T readLeniently(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }
}

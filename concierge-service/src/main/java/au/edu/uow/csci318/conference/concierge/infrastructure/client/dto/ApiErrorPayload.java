package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The platform's common error body, {@code {"code", "message"}}, as every service writes it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiErrorPayload(String code, String message) {
}

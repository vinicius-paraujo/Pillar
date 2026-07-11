package br.com.markineo.pillar.core.messaging;

import java.util.UUID;

public record SendPlayerMessageRequest(UUID playerId, String miniMessageContent) {
}

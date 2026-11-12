package com.mojud.shared.logging;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the request log writes (docs/logging.md).
 *
 * @param bodyLimit how much of each body a line shows, in bytes; the rest is counted, not written.
 *     Servers show the start of each body; local runs show nearly all of it.
 * @param quietPaths paths whose calls are not logged, such as the health check a container runs
 *     every few seconds
 */
@ConfigurationProperties("mojud.logging")
public record LoggingProperties(@DefaultValue("4096") int bodyLimit, @DefaultValue("/healthz") List<String> quietPaths) {
}

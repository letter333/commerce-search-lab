package org.letter33.commercesearchlab.search;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("search.elasticsearch")
public record ElasticsearchProperties(String url, String username, String passwordFile, String caCertificate,
		@DefaultValue("5s") Duration connectTimeout, @DefaultValue("40s") Duration requestTimeout) {

	public URI validatedEndpoint() {
		if (url == null || url.isBlank()) {
			throw invalidEndpoint();
		}
		URI endpoint;
		try {
			endpoint = new URI(url);
		}
		catch (URISyntaxException exception) {
			// URI parsing errors contain the original input, which may include credentials.
			throw invalidEndpoint();
		}
		String path = endpoint.getRawPath();
		if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
				|| endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
				|| endpoint.getRawFragment() != null || (path != null && !path.isEmpty() && !"/".equals(path))
				|| endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
			throw invalidEndpoint();
		}
		if (username == null || username.isBlank() || username.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					"search.elasticsearch.username must be nonblank and contain no control characters");
		}
		requirePath(passwordFile, "password-file");
		requirePath(caCertificate, "ca-certificate");
		requireTimeout(connectTimeout, "connect-timeout");
		requireTimeout(requestTimeout, "request-timeout");
		return endpoint;
	}

	@Override
	public String toString() {
		return "ElasticsearchProperties[redacted]";
	}

	private static void requirePath(String path, String property) {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("search.elasticsearch." + property + " must be nonblank");
		}
	}

	private static void requireTimeout(Duration timeout, String property) {
		if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
				|| timeout.compareTo(Duration.ofMinutes(5)) > 0) {
			throw new IllegalArgumentException("search.elasticsearch." + property + " must be between 1ms and 5m");
		}
	}

	private static IllegalArgumentException invalidEndpoint() {
		return new IllegalArgumentException(
				"search.elasticsearch.url must be an HTTPS origin without credentials, query or fragment");
	}
}

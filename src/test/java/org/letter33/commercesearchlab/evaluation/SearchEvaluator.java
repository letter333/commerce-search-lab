package org.letter33.commercesearchlab.evaluation;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Serial, reproducible development-seed diagnostics. Quality failures are distinct from API failures. */
final class SearchEvaluator {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final Set<String> FILTERS = Set.of("price_min", "price_max", "color", "category");

    record Options(URI baseUrl, Path output, List<String> modes, int warmup,
                   boolean acceptSuggestions, Duration timeout, Path catalog, Path scenarios) {}
    record WireResponse(int status, String body, double elapsedMs) {}
    @FunctionalInterface interface Transport {
        WireResponse get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }
    record Report(boolean valid, Map<String, Object> summary) {}
    record Scenario(String id, String query, int page, int size, Map<String, Object> filters,
                    Set<String> relevant, boolean zeroExpected, boolean correctionExpected,
                    Set<String> acceptableSuggestions) {}
    record Response(String requestId, String originalQuery, String executedQuery, List<String> suggestions,
                    List<String> ids, long total, double serverMs, String configVersion, String indexVersion) {}
    record Attempt(Scenario scenario, String mode, String stage, boolean warmup, Response response,
                   String error, double elapsedMs) {
        boolean success() { return error == null; }
    }
    private final Options options;
    private final Transport transport;
    private final Map<String, JsonNode> products = new LinkedHashMap<>();
    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private final Map<String, String> configVersions = new LinkedHashMap<>();
    private String indexVersion;

    SearchEvaluator(Options options) {
        this(options, httpTransport(options.timeout()));
    }

    SearchEvaluator(Options options, Transport transport) {
        this.options = options;
        this.transport = transport;
    }

    static Transport httpTransport(Duration timeout) {
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, requestTimeout) -> {
            long start = System.nanoTime();
            var response = client.send(HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new WireResponse(response.statusCode(), response.body(), (System.nanoTime() - start) / 1_000_000.0);
        };
    }

    Report run() throws Exception {
        byte[] catalogBytes = Files.readAllBytes(options.catalog());
        byte[] scenarioBytes = Files.readAllBytes(options.scenarios());
        var catalog = JSON.readTree(catalogBytes);
        var queries = JSON.readTree(scenarioBytes);
        load(catalog, queries);
        Path output = options.output().toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output); // Deliberately fail instead of overwriting earlier evidence.
        Map<String, Object> manifest = manifest(catalog, queries, catalogBytes, scenarioBytes);
        writeJson(output.resolve("manifest.json"), manifest);
        try (BufferedWriter log = Files.newBufferedWriter(output.resolve("runs.jsonl"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            for (String mode : options.modes()) {
                for (int pass = 0; pass <= options.warmup(); pass++) {
                    boolean warmup = pass < options.warmup();
                    for (Scenario scenario : scenarios) {
                        Attempt first = execute(scenario, mode, "original", warmup, pass, scenario.query(), null, log);
                        if (options.acceptSuggestions() && scenario.correctionExpected() && first.success()
                                && !first.response().suggestions().isEmpty()) {
                            execute(scenario, mode, "suggestion_accepted", warmup, pass,
                                    first.response().suggestions().getFirst(), first.response().requestId(), log);
                        }
                    }
                }
            }
        }
        boolean valid = attempts.stream().allMatch(Attempt::success);
        Map<String, Object> summary = summary(valid);
        writeJson(output.resolve("summary.json"), summary);
        manifest.put("finished_at", Instant.now().toString());
        manifest.put("status", valid ? "complete" : "failed");
        manifest.put("config_versions_by_mode", configVersions);
        manifest.put("index_version", indexVersion);
        writeJson(output.resolve("manifest.json"), manifest);
        return new Report(valid, summary);
    }

    private void load(JsonNode catalog, JsonNode queries) {
        String catalogId = requiredText(catalog, "dataset_id");
        require(catalogId.equals(requiredText(queries, "catalog_id")), "Scenario catalog_id differs from catalog dataset_id");
        requiredText(queries, "dataset_id");
        for (JsonNode product : requiredArray(catalog, "products")) {
            String id = requiredText(product, "id");
            require(products.put(id, product) == null, "Duplicate catalog product: " + id);
            requiredText(product, "color");
            requiredText(product, "category");
            requiredNonnegativeInteger(product, "price_krw");
            require(product.path("active").isBoolean(), "Product active must be boolean: " + id);
        }
        require(!products.isEmpty(), "Catalog is empty");
        Set<String> ids = new HashSet<>();
        for (JsonNode node : requiredArray(queries, "cases")) {
            String id = requiredText(node, "id");
            require(ids.add(id), "Duplicate scenario: " + id);
            requiredText(node, "intent_group");
            require(requiredText(node, "split").equals("development"),
                    "Only development scenarios are supported; final/mixed splits require a frozen evaluation protocol: " + id);
            var request = node.path("request");
            String q = requiredText(request, "q");
            int page = Math.toIntExact(requiredNonnegativeInteger(request, "page"));
            int size = Math.toIntExact(requiredNonnegativeInteger(request, "size"));
            require(page == 1 && size >= 10 && size <= 100, "Evaluation requires page=1 and size in [10,100]: " + id);
            JsonNode filtersNode = request.path("filters");
            require(filtersNode.isObject(), "filters must be an object: " + id);
            Map<String, Object> filters = new LinkedHashMap<>();
            for (var entry : filtersNode.properties()) {
                String key = entry.getKey();
                require(FILTERS.contains(key), "Unknown filter: " + key);
                filters.put(key, key.startsWith("price_") ? requiredNonnegativeInteger(filtersNode, key)
                        : requiredText(filtersNode, key));
            }
            require(!filters.containsKey("price_min") || !filters.containsKey("price_max")
                    || (long) filters.get("price_min") <= (long) filters.get("price_max"), "Invalid price range: " + id);
            var expected = node.path("expected");
            Set<String> relevant = new HashSet<>(strings(requiredArray(expected, "relevant_product_ids")));
            require(products.keySet().containsAll(relevant), "Unknown relevant product: " + id);
            require(expected.path("expected_zero_results").isBoolean(), "expected_zero_results must be boolean: " + id);
            boolean zero = expected.path("expected_zero_results").booleanValue();
            require(zero == relevant.isEmpty(), "Expected zero and relevant IDs disagree: " + id);
            var correction = expected.path("correction");
            String policy = requiredText(correction, "policy");
            require(Set.of("suggest_only", "no_spelling_suggestion").contains(policy), "Unknown correction policy: " + id);
            require(correction.path("auto_apply").isBoolean() && !correction.path("auto_apply").booleanValue(),
                    "auto_apply must be false: " + id);
            Set<String> acceptable = new HashSet<>(strings(requiredArray(correction, "acceptable_suggestions")));
            boolean correct = policy.equals("suggest_only");
            require(correct != acceptable.isEmpty(), "Correction policy and accepted suggestions disagree: " + id);
            scenarios.add(new Scenario(id, q, page, size, filters, relevant, zero, correct, acceptable));
        }
        require(!scenarios.isEmpty(), "Scenario set is empty");
    }

    private Attempt execute(Scenario scenario, String mode, String stage, boolean warmup, int pass,
                            String query, String parentRequestId, BufferedWriter log) throws IOException {
        URI uri = requestUri(options.baseUrl(), scenario, query, mode);
        Map<String, Object> row = map("scenario_id", scenario.id(), "mode", mode, "stage", stage,
                "warmup", warmup, "pass", pass, "started_at", Instant.now().toString(),
                "scenario_query", scenario.query(), "request_query", query, "request_url", uri.toASCIIString(),
                "filters", scenario.filters(), "page", scenario.page(), "size", scenario.size(),
                "parent_request_id", parentRequestId);
        Response parsed = null;
        String error = null;
        long started = System.nanoTime();
        double elapsed;
        try {
            WireResponse response = transport.get(uri, options.timeout());
            row.put("http_status", response.status());
            row.put("response_body", response.body());
            row.put("client_elapsed_ms", response.elapsedMs());
            if (response.status() != 200) throw new EvaluationFailure("http_error", "HTTP " + response.status());
            parsed = parseResponse(response.body(), scenario, query);
            checkVersions(mode, parsed);
            if (stage.equals("original") && scenario.correctionExpected()
                    && !normalizedSpelling(query).equals(normalizedSpelling(parsed.executedQuery()))) {
                throw new EvaluationFailure("contract_error", "Spelling correction was applied before explicit acceptance");
            }
            row.put("response", JSON.readTree(response.body()));
            row.put("retrieval", retrieval(scenario, parsed));
            row.put("status", "success");
        } catch (HttpTimeoutException e) {
            error = "timeout";
            row.put("error_message", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
            row.put("error_message", e.toString());
        } catch (EvaluationFailure e) {
            error = e.kind;
            row.put("error_message", e.getMessage());
        } catch (IOException e) {
            error = "transport_error";
            row.put("error_message", e.toString());
        } catch (RuntimeException e) {
            error = "contract_error";
            row.put("error_message", e.getMessage());
        }
        elapsed = row.containsKey("client_elapsed_ms") ? (double) row.get("client_elapsed_ms")
                : (System.nanoTime() - started) / 1_000_000.0;
        row.put("client_elapsed_ms", elapsed);
        if (error != null) {
            row.put("status", "failed");
            row.put("error_type", error);
        }
        Attempt attempt = new Attempt(scenario, mode, stage, warmup, parsed, error, elapsed);
        attempts.add(attempt);
        log.write(JSON.writeValueAsString(row));
        log.newLine();
        log.flush();
        return attempt;
    }

    static URI requestUri(URI baseUrl, Scenario scenario, String query, String mode) {
        Map<String, Object> parameters = map("q", query, "mode", mode, "page", scenario.page(), "size", scenario.size());
        parameters.putAll(scenario.filters());
        String encoded = parameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().toString()))
                .collect(java.util.stream.Collectors.joining("&"));
        return baseUrl.resolve("/api/search?" + encoded);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizedSpelling(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private Response parseResponse(String body, Scenario scenario, String query) {
        JsonNode response = JSON.readTree(body);
        require(response != null && response.isObject(), "Response must be a JSON object");
        String requestId = requiredText(response, "request_id");
        String original = requiredText(response, "original_query");
        require(original.equals(query), "original_query must exactly preserve request q, including whitespace");
        String executed = requiredText(response, "executed_query");
        List<String> suggestions = strings(requiredArray(response, "suggestions"));
        long total = requiredNonnegativeInteger(response, "total");
        JsonNode timing = response.path("timing_ms");
        require(timing.isNumber() && Double.isFinite(timing.doubleValue()) && timing.doubleValue() >= 0,
                "timing_ms must be a finite nonnegative number");
        String config = requiredText(response, "config_version");
        String index = requiredText(response, "index_version");
        List<String> ids = new ArrayList<>();
        for (JsonNode item : requiredArray(response, "items")) {
            String id = requiredText(item, "id");
            require(!ids.contains(id), "Duplicate result ID: " + id);
            JsonNode product = products.get(id);
            require(product != null, "Unknown result ID: " + id);
            require(product.path("active").booleanValue(), "Inactive product returned: " + id);
            for (var filter : scenario.filters().entrySet()) {
                boolean matches = switch (filter.getKey()) {
                    case "price_min" -> product.path("price_krw").longValue() >= (long) filter.getValue();
                    case "price_max" -> product.path("price_krw").longValue() <= (long) filter.getValue();
                    default -> product.path(filter.getKey()).stringValue().equals(filter.getValue());
                };
                if (!matches) throw new EvaluationFailure("filter_violation", "Product " + id + " violates " + filter.getKey());
            }
            ids.add(id);
        }
        require(ids.size() == Math.min(total, scenario.size()), "items count must equal min(total,size) on page 1");
        return new Response(requestId, original, executed, suggestions, ids, total, timing.doubleValue(), config, index);
    }

    private void checkVersions(String mode, Response response) {
        String prior = configVersions.get(mode);
        if (prior != null && !prior.equals(response.configVersion())) {
            throw new EvaluationFailure("version_changed", "config_version changed during mode " + mode);
        }
        if (indexVersion != null && !indexVersion.equals(response.indexVersion())) {
            throw new EvaluationFailure("version_changed", "index_version changed during evaluation");
        }
        configVersions.putIfAbsent(mode, response.configVersion());
        indexVersion = response.indexVersion();
    }

    static Map<String, Object> retrieval(Scenario scenario, Response response) {
        int first = 0;
        for (int i = 0; i < Math.min(10, response.ids().size()); i++) {
            if (scenario.relevant().contains(response.ids().get(i))) { first = i + 1; break; }
        }
        return map("hit_at_5", scenario.zeroExpected() ? null : first > 0 && first <= 5 ? 1 : 0,
                "reciprocal_rank_at_10", scenario.zeroExpected() ? null : first > 0 ? 1.0 / first : 0.0,
                "unexpected_results", scenario.zeroExpected() && response.total() > 0);
    }

    private Map<String, Object> summary(boolean valid) {
        Map<String, Object> modes = new LinkedHashMap<>();
        for (String mode : options.modes()) {
            var modeAttempts = attempts.stream().filter(a -> a.mode().equals(mode)).toList();
            boolean modeValid = modeAttempts.stream().allMatch(Attempt::success);
            var original = modeAttempts.stream().filter(a -> !a.warmup() && a.stage().equals("original")).toList();
            var accepted = modeAttempts.stream().filter(a -> !a.warmup() && a.stage().equals("suggestion_accepted")).toList();
            long eligible = scenarios.stream().filter(Scenario::correctionExpected).count();
            long withoutSuggestion = original.stream().filter(a -> a.scenario().correctionExpected()
                    && a.success() && a.response().suggestions().isEmpty()).count();
            Map<String, Object> acceptedSummary = summarizeStage(accepted, modeValid);
            acceptedSummary.put("enabled", options.acceptSuggestions());
            acceptedSummary.put("eligible_correction_queries", eligible);
            acceptedSummary.put("queries_without_suggestion", withoutSuggestion);
            acceptedSummary.put("selected_queries", accepted.size());
            acceptedSummary.put("selection_coverage", modeValid && options.acceptSuggestions()
                    ? ratio(accepted.size(), Math.toIntExact(eligible)) : null);
            Map<String, Object> modeSummary = map("valid", modeValid,
                    "failures_including_warmup", modeAttempts.stream().filter(a -> !a.success()).count(),
                    "warmup_requests", modeAttempts.stream().filter(Attempt::warmup).count(),
                    "original", summarizeStage(original, modeValid),
                    "suggestion_accepted", acceptedSummary);
            modes.put(mode, modeSummary);
        }
        return map("schema_version", "1.0", "valid", valid, "purpose", "development_seed_diagnostics_not_load_test",
                "quality_metrics_policy", "null if any request, contract or warmup failure occurs in that mode; never score failures as zero results",
                "p95_method", "nearest_rank_ceil_0.95n; measured successful requests only; serial; excludes warmup",
                "scenario_count", scenarios.size(), "modes", modes);
    }

    static Map<String, Object> summarizeStage(List<Attempt> rows, boolean valid) {
        var success = rows.stream().filter(Attempt::success).toList();
        var answerable = rows.stream().filter(a -> !a.scenario().zeroExpected()).toList();
        var noAnswer = rows.stream().filter(a -> a.scenario().zeroExpected()).toList();
        var correction = rows.stream().filter(a -> a.scenario().correctionExpected()).toList();
        var normal = rows.stream().filter(a -> !a.scenario().correctionExpected()).toList();
        var suggested = correction.stream().filter(a -> a.success() && !a.response().suggestions().isEmpty()).toList();
        long correct = suggested.stream().filter(a -> a.scenario().acceptableSuggestions().contains(a.response().suggestions().getFirst())).count();
        long falseSuggestions = normal.stream().filter(a -> a.success() && !a.response().suggestions().isEmpty()).count();
        double hit = 0;
        double mrr = 0;
        for (Attempt row : answerable) {
            if (!row.success()) continue;
            var scores = retrieval(row.scenario(), row.response());
            hit += ((Number) scores.get("hit_at_5")).doubleValue();
            mrr += ((Number) scores.get("reciprocal_rank_at_10")).doubleValue();
        }
        // Suggestion correctness is defined only for original requests, not recursive suggestions after acceptance.
        boolean original = rows.isEmpty() || rows.getFirst().stage().equals("original");
        return map("requests", rows.size(), "successful_requests", success.size(), "failed_requests", rows.size() - success.size(),
                "answerable_queries", answerable.size(), "hit_at_5", valid ? ratio(hit, answerable.size()) : null,
                "mrr_at_10", valid ? ratio(mrr, answerable.size()) : null,
                "no_answer_queries", noAnswer.size(),
                "no_answer_unexpected_results", valid ? noAnswer.stream().filter(a -> a.response().total() > 0).count() : null,
                "correction_queries", original ? correction.size() : null,
                "queries_with_suggestions", original && valid ? suggested.size() : null,
                "suggestion_accuracy", original && valid ? ratio(correct, suggested.size()) : null,
                "suggestion_coverage", original && valid ? ratio(suggested.size(), correction.size()) : null,
                "normal_queries", original ? normal.size() : null,
                "normal_false_suggestion_rate", original && valid ? ratio(falseSuggestions, normal.size()) : null,
                "client_p95_ms", percentile95(success.stream().map(Attempt::elapsedMs).toList()),
                "latency_sample_count", success.size());
    }

    static Double percentile95(List<Double> values) {
        if (values.isEmpty()) return null;
        var sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private Map<String, Object> manifest(JsonNode catalog, JsonNode queries, byte[] catalogBytes, byte[] scenarioBytes) throws Exception {
        return map("schema_version", "1.0", "status", "running", "started_at", Instant.now().toString(),
                "base_url", options.baseUrl().toString(), "modes", options.modes(),
                "accept_suggestions", options.acceptSuggestions(), "warmup_passes_per_mode", options.warmup(),
                "concurrency", 1, "measured_passes_per_mode", 1, "timeout_ms", options.timeout().toMillis(),
                "catalog", map("path", options.catalog().toAbsolutePath().toString(), "dataset_id", requiredText(catalog, "dataset_id"),
                        "sha256", sha256(catalogBytes), "product_count", products.size()),
                "scenarios", map("path", options.scenarios().toAbsolutePath().toString(), "dataset_id", requiredText(queries, "dataset_id"),
                        "sha256", sha256(scenarioBytes), "case_count", scenarios.size()),
                "environment", map("java_version", System.getProperty("java.version"), "java_vendor", System.getProperty("java.vendor"),
                        "os", System.getProperty("os.name"), "os_version", System.getProperty("os.version"),
                        "architecture", System.getProperty("os.arch"), "available_processors", Runtime.getRuntime().availableProcessors(),
                        "jvm_max_memory_bytes", Runtime.getRuntime().maxMemory()),
                "limitations", List.of("Development seed and draft judgments; not a final evaluation set",
                        "Serial diagnostic latency, not a load benchmark", "Accepted suggestions do not measure real user acceptance",
                        "config_version is stable within each mode; index_version is stable across the whole run"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Double ratio(double numerator, int denominator) { return denominator == 0 ? null : numerator / denominator; }

    static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }

    private static String requiredText(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        require(node.isString() && !node.stringValue().isBlank(), key + " must be a nonblank string");
        return node.stringValue();
    }

    private static JsonNode requiredArray(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        require(node.isArray(), key + " must be an array");
        return node;
    }

    private static long requiredNonnegativeInteger(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0, key + " must be a nonnegative integer");
        return node.longValue();
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            require(item.isString() && !item.stringValue().isBlank(), "Array must contain nonblank strings");
            require(!result.contains(item.stringValue()), "Duplicate value in string array: " + item.stringValue());
            result.add(item.stringValue());
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class EvaluationFailure extends RuntimeException {
        private final String kind;
        EvaluationFailure(String kind, String message) { super(message); this.kind = kind; }
    }
}

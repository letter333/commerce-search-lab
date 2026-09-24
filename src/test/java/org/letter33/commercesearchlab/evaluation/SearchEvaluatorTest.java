package org.letter33.commercesearchlab.evaluation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.letter33.commercesearchlab.evaluation.SearchEvaluator.JSON;
import static org.letter33.commercesearchlab.evaluation.SearchEvaluator.map;

class SearchEvaluatorTest {
    @TempDir Path temp;

    @Test
    void fixedSeedDenominatorsAndNoSuggestionNAExcludeWarmupFromLatency() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var options = options("without-suggestions", 1, false);
        var report = new SearchEvaluator(options, (uri, timeout) -> wire(uri, List.of(), List.of(),
                calls.incrementAndGet() <= 30 ? 100_000 : calls.get() - 30)).run();

        assertThat(report.valid()).isTrue();
        JsonNode summary = summary(options);
        JsonNode original = summary.at("/modes/baseline/original");
        assertThat(original.path("answerable_queries").intValue()).isEqualTo(27);
        assertThat(original.path("no_answer_queries").intValue()).isEqualTo(3);
        assertThat(original.path("correction_queries").intValue()).isEqualTo(6);
        assertThat(original.path("normal_queries").intValue()).isEqualTo(24);
        assertThat(original.path("hit_at_5").doubleValue()).isZero();
        assertThat(original.path("mrr_at_10").doubleValue()).isZero();
        assertThat(original.path("suggestion_accuracy").isNull()).isTrue();
        assertThat(original.path("suggestion_coverage").doubleValue()).isZero();
        assertThat(original.path("no_answer_unexpected_results").intValue()).isZero();
        assertThat(original.path("client_p95_ms").doubleValue()).isEqualTo(29);
        assertThat(original.path("latency_sample_count").intValue()).isEqualTo(30);
        assertThat(calls).hasValue(60);
        assertThat(Files.readAllLines(options.output().resolve("runs.jsonl"))).hasSize(60);
        JsonNode manifest = JSON.readTree(Files.readString(options.output().resolve("manifest.json")));
        assertThat(manifest.at("/catalog/sha256").stringValue()).hasSize(64);
        assertThat(manifest.at("/scenarios/dataset_id").stringValue()).isEqualTo("queries-dev-v0.1");
        assertThat(manifest.path("index_version").stringValue()).isEqualTo("index-v1");
        assertThat(manifest.at("/config_versions_by_mode/baseline").stringValue()).isEqualTo("config-v1");
    }

    @Test
    void hitAndMrrUseRankCutoffsAndDoNotIncludeNoAnswerCases() {
        var scenario = scenario(Set.of("P001"), false);
        var ids = new ArrayList<>(List.of("P002", "P003", "P004", "P005", "P006", "P001"));
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("hit_at_5")).isEqualTo(0);
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("reciprocal_rank_at_10")).isEqualTo(1.0 / 6);
        ids.remove("P006");
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("hit_at_5")).isEqualTo(1);
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("reciprocal_rank_at_10")).isEqualTo(0.2);
        ids.addFirst(ids.removeLast());
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("hit_at_5")).isEqualTo(1);
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("reciprocal_rank_at_10")).isEqualTo(1.0);
        ids = new ArrayList<>(List.of("P002", "P003", "P004", "P005", "P006", "P007", "P008", "P009", "P010", "P001"));
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("reciprocal_rank_at_10")).isEqualTo(0.1);
        ids.addFirst("P011");
        assertThat(SearchEvaluator.retrieval(scenario, response(ids)).get("reciprocal_rank_at_10")).isEqualTo(0.0);
        var noAnswer = SearchEvaluator.retrieval(scenario(Set.of(), true), response(List.of("P001")));
        assertThat(noAnswer.get("hit_at_5")).isNull();
        assertThat(noAnswer.get("reciprocal_rank_at_10")).isNull();
        assertThat(noAnswer.get("unexpected_results")).isEqualTo(true);
    }

    @Test
    void explicitlyAcceptsActualWrongTopSuggestionAndKeepsStagesSeparate() throws Exception {
        String actualCandidate = "틀린+후보 & 셔츠";
        List<String> requested = new ArrayList<>();
        var options = options("accepted", 0, true);
        new SearchEvaluator(options, (uri, timeout) -> {
            String query = parameters(uri).get("q");
            requested.add(query);
            // One erroneous normal-input suggestion must count as a false suggestion, not another acceptance.
            List<String> suggestions = query.equals("맨투먼") ? List.of(actualCandidate, "맨투맨")
                    : query.equals("MONOLIT") ? List.of("잘못된 브랜드 교정") : List.of();
            return wire(uri, List.of(), suggestions, query.equals(actualCandidate) ? 99 : 5);
        }).run();

        assertThat(requested).hasSize(31).contains(actualCandidate).doesNotContain("잘못된 브랜드 교정");
        assertThat(requested.get(requested.indexOf("맨투먼") + 1)).isEqualTo(actualCandidate);
        JsonNode summary = summary(options);
        JsonNode original = summary.at("/modes/baseline/original");
        JsonNode accepted = summary.at("/modes/baseline/suggestion_accepted");
        assertThat(original.path("answerable_queries").intValue()).isEqualTo(27);
        assertThat(original.path("suggestion_accuracy").doubleValue()).isZero();
        assertThat(original.path("suggestion_coverage").doubleValue()).isEqualTo(1.0 / 6);
        assertThat(original.path("normal_false_suggestion_rate").doubleValue()).isEqualTo(1.0 / 24);
        assertThat(original.path("client_p95_ms").doubleValue()).isEqualTo(5);
        assertThat(accepted.path("requests").intValue()).isEqualTo(1);
        assertThat(accepted.path("eligible_correction_queries").intValue()).isEqualTo(6);
        assertThat(accepted.path("queries_without_suggestion").intValue()).isEqualTo(5);
        assertThat(accepted.path("selection_coverage").doubleValue()).isEqualTo(1.0 / 6);
        assertThat(accepted.path("client_p95_ms").doubleValue()).isEqualTo(99);
        JsonNode acceptedRow = Files.readAllLines(options.output().resolve("runs.jsonl")).stream().map(JSON::readTree)
                .filter(row -> row.path("stage").stringValue().equals("suggestion_accepted")).findFirst().orElseThrow();
        assertThat(acceptedRow.path("scenario_query").stringValue()).isEqualTo("맨투먼");
        assertThat(acceptedRow.path("request_query").stringValue()).isEqualTo(actualCandidate);
        assertThat(acceptedRow.path("parent_request_id").stringValue()).isEqualTo("request-맨투먼");
    }

    @Test
    void suggestionAccuracyUsesTopOneOnlyAndRetrievalMeansHaveCorrectDenominator() {
        var typo = new SearchEvaluator.Scenario("t", "오탸", 1, 10, Map.of(), Set.of("P001"), false, true, Set.of("오타"));
        var normal = scenario(Set.of("P001"), false);
        var noAnswer = scenario(Set.of(), true);
        var right = new SearchEvaluator.Response("1", "오탸", "오탸", List.of("오타"), List.of("P001"), 1, 1, "c", "i");
        var wrong = new SearchEvaluator.Response("2", "오탸", "오탸", List.of("틀림", "오타"), List.of("P002", "P001"), 2, 1, "c", "i");
        var rows = List.of(attempt(typo, right), attempt(typo, wrong), attempt(normal, response(List.of())), attempt(noAnswer, response(List.of("P001"))));
        var stats = SearchEvaluator.summarizeStage(rows, true);
        assertThat(stats.get("hit_at_5")).isEqualTo(2.0 / 3);
        assertThat(stats.get("mrr_at_10")).isEqualTo(0.5);
        assertThat(stats.get("suggestion_accuracy")).isEqualTo(0.5);
        assertThat(stats.get("suggestion_coverage")).isEqualTo(1.0);
        assertThat(stats.get("no_answer_unexpected_results")).isEqualTo(1L);
    }

    @Test
    void suggestionsAreMeasuredButNeverExecutedWithoutExplicitFlag() throws Exception {
        var options = options("suggestions-disabled", 0, false);
        AtomicInteger calls = new AtomicInteger();
        new SearchEvaluator(options, (uri, timeout) -> {
            calls.incrementAndGet();
            return wire(uri, List.of(), parameters(uri).get("q").equals("맨투먼") ? List.of("맨투맨") : List.of(), 1);
        }).run();
        assertThat(calls).hasValue(30);
        assertThat(summary(options).at("/modes/baseline/original/suggestion_accuracy").doubleValue()).isEqualTo(1);
        assertThat(summary(options).at("/modes/baseline/suggestion_accepted/requests").intValue()).isZero();
        assertThat(summary(options).at("/modes/baseline/suggestion_accepted/selection_coverage").isNull()).isTrue();
    }

    @Test
    void modesMayHaveDifferentConfigVersionsButMustShareTheSameIndex() throws Exception {
        var defaults = options("multi-mode", 0, false);
        var options = new SearchEvaluator.Options(defaults.baseUrl(), defaults.output(), List.of("baseline", "query_v1"),
                0, false, defaults.timeout(), defaults.catalog(), defaults.scenarios());
        var report = new SearchEvaluator(options, (uri, timeout) -> {
            var parameters = parameters(uri);
            var body = body(parameters.get("q"), List.of(), List.of());
            body.put("config_version", parameters.get("mode") + "-config");
            return new SearchEvaluator.WireResponse(200, JSON.writeValueAsString(body), 1);
        }).run();
        assertThat(report.valid()).isTrue();
        var manifest = JSON.readTree(Files.readString(options.output().resolve("manifest.json")));
        assertThat(manifest.at("/config_versions_by_mode/baseline").stringValue()).isEqualTo("baseline-config");
        assertThat(manifest.at("/config_versions_by_mode/query_v1").stringValue()).isEqualTo("query_v1-config");
    }

    @ParameterizedTest
    @ValueSource(strings = {"503", "504", "timeout", "io", "malformed", "missing", "unknown", "duplicate",
            "total_mismatch", "negative_total", "wrong_original", "wrong_timing", "duplicate_json", "trailing_json"})
    void requestFailuresArePreservedAndNeverBecomeZeroResultSuccess(String failure) throws Exception {
        var options = options("failure-" + failure, 0, false);
        AtomicInteger calls = new AtomicInteger();
        var report = new SearchEvaluator(options, (uri, timeout) -> {
            if (calls.incrementAndGet() != 1) return wire(uri, List.of(), List.of(), 1);
            if (failure.equals("timeout")) throw new HttpTimeoutException("deliberate timeout");
            if (failure.equals("io")) throw new IOException("deliberate transport failure");
            if (failure.equals("503") || failure.equals("504")) return new SearchEvaluator.WireResponse(Integer.parseInt(failure), "unavailable", 1);
            Map<String, Object> body = body(parameters(uri).get("q"), List.of(), List.of());
            switch (failure) {
                case "malformed" -> { return new SearchEvaluator.WireResponse(200, "not JSON", 1); }
                case "missing" -> body.remove("suggestions");
                case "unknown" -> { body.put("items", List.of(Map.of("id", "P999"))); body.put("total", 1); }
                case "duplicate" -> { body.put("items", List.of(Map.of("id", "P001"), Map.of("id", "P001"))); body.put("total", 2); }
                case "total_mismatch" -> body.put("total", 1);
                case "negative_total" -> body.put("total", -1);
                case "wrong_original" -> body.put("original_query", "different");
                case "wrong_timing" -> body.put("timing_ms", -1);
                case "duplicate_json" -> { return new SearchEvaluator.WireResponse(200, "{\"total\":0,\"total\":1}", 1); }
                case "trailing_json" -> { return new SearchEvaluator.WireResponse(200, JSON.writeValueAsString(body) + " {}", 1); }
                default -> throw new AssertionError(failure);
            }
            return new SearchEvaluator.WireResponse(200, JSON.writeValueAsString(body), 1);
        }).run();

        assertThat(report.valid()).isFalse();
        JsonNode original = summary(options).at("/modes/baseline/original");
        assertThat(original.path("failed_requests").intValue()).isEqualTo(1);
        assertThat(original.path("successful_requests").intValue()).isEqualTo(29);
        assertThat(original.path("hit_at_5").isNull()).isTrue();
        assertThat(original.path("no_answer_unexpected_results").isNull()).isTrue();
        JsonNode first = JSON.readTree(Files.readAllLines(options.output().resolve("runs.jsonl")).getFirst());
        assertThat(first.path("status").stringValue()).isEqualTo("failed");
        assertThat(first.has("retrieval")).isFalse();
        assertThat(first.path("error_type").stringValue()).isNotBlank();
        assertThat(JSON.readTree(Files.readString(options.output().resolve("manifest.json"))).path("status").stringValue()).isEqualTo("failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"price_min", "price_max", "color", "category"})
    void enforcesEveryFilterAgainstSnapshotRatherThanTrustingReturnedFields(String filter) throws Exception {
        Object value = switch (filter) {
            case "price_min" -> 30_000;
            case "price_max" -> 28_000;
            case "color" -> "핑크";
            default -> "후드티";
        };
        Path scenarios = singleScenario(filter, value);
        var defaults = options("filter-" + filter, 0, false);
        var options = new SearchEvaluator.Options(defaults.baseUrl(), defaults.output(), defaults.modes(), 0,
                false, defaults.timeout(), defaults.catalog(), scenarios);
        var report = new SearchEvaluator(options, (uri, timeout) -> {
            assertThat(parameters(uri).get(filter)).isEqualTo(value.toString());
            return wire(uri, List.of("P001"), List.of(), 1);
        }).run();
        assertThat(report.valid()).isFalse();
        JsonNode row = JSON.readTree(Files.readAllLines(options.output().resolve("runs.jsonl")).getFirst());
        assertThat(row.path("error_type").stringValue()).isEqualTo("filter_violation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"config_version", "index_version"})
    void rejectsVersionDriftEvenFromWarmup(String field) throws Exception {
        var options = options("drift-" + field, 1, false);
        AtomicInteger calls = new AtomicInteger();
        var report = new SearchEvaluator(options, (uri, timeout) -> {
            Map<String, Object> body = body(parameters(uri).get("q"), List.of(), List.of());
            if (calls.incrementAndGet() > 30) body.put(field, "changed");
            return new SearchEvaluator.WireResponse(200, JSON.writeValueAsString(body), 1);
        }).run();
        assertThat(report.valid()).isFalse();
        JsonNode original = summary(options).at("/modes/baseline/original");
        assertThat(original.path("successful_requests").intValue()).isZero();
        assertThat(original.path("hit_at_5").isNull()).isTrue();
        assertThat(original.path("client_p95_ms").isNull()).isTrue();
    }

    @Test
    void failedWarmupInvalidatesOtherwiseSuccessfulMeasuredRun() throws Exception {
        var options = options("warmup-error", 1, false);
        AtomicInteger calls = new AtomicInteger();
        var report = new SearchEvaluator(options, (uri, timeout) -> calls.incrementAndGet() == 1
                ? new SearchEvaluator.WireResponse(503, "unavailable", 1) : wire(uri, List.of(), List.of(), 1)).run();
        assertThat(report.valid()).isFalse();
        JsonNode original = summary(options).at("/modes/baseline/original");
        assertThat(original.path("successful_requests").intValue()).isEqualTo(30);
        assertThat(original.path("hit_at_5").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"맨투맨", "잘못된 자동 교정"})
    void rejectsBothCorrectAndIncorrectAutomaticCorrection(String replacement) throws Exception {
        var options = options("automatic-" + replacement, 0, false);
        var report = new SearchEvaluator(options, (uri, timeout) -> {
            String query = parameters(uri).get("q");
            var body = body(query, List.of(), List.of());
            if (query.equals("맨투먼")) body.put("executed_query", replacement);
            return new SearchEvaluator.WireResponse(200, JSON.writeValueAsString(body), 1);
        }).run();
        assertThat(report.valid()).isFalse();
        assertThat(summary(options).at("/modes/baseline/original/failed_requests").intValue()).isEqualTo(1);
    }

    @Test
    void clientSendsUnicodeWhitespaceAndAllFiltersOverHttpWithoutChangingTheirMeaning() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search", exchange -> {
            received.set(exchange.getRequestURI().toASCIIString());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            String query = "  한글 + & ?   티셔츠  ";
            var scenario = new SearchEvaluator.Scenario("encoding", query, 1, 10,
                    map("price_min", 1000L, "price_max", 90000L, "color", "블랙", "category", "티셔츠"), Set.of("P001"), false, false, Set.of());
            URI uri = SearchEvaluator.requestUri(origin, scenario, query, "ranking_v1");
            var response = SearchEvaluator.httpTransport(Duration.ofSeconds(2)).get(uri, Duration.ofSeconds(2));
            assertThat(response.status()).isEqualTo(200);
            assertThat(parameters(URI.create(received.get()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "q", query, "mode", "ranking_v1", "page", "1", "size", "10", "price_min", "1000",
                    "price_max", "90000", "color", "블랙", "category", "티셔츠"));
        } finally { server.stop(0); }
    }

    @Test
    void cliReturnsNonzeroForServiceErrorsAndRefusesToOverwriteReports() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
            Path output = temp.resolve("cli");
            String[] args = {"--base-url", "http://127.0.0.1:" + server.getAddress().getPort(), "--warmup", "0", "--output", output.toString()};
            assertThat(SearchEvaluationCli.run(args, sink, sink)).isEqualTo(1);
            assertThat(output.resolve("summary.json")).exists();
            String before = Files.readString(output.resolve("runs.jsonl"));
            assertThat(SearchEvaluationCli.run(args, sink, sink)).isEqualTo(2);
            assertThat(Files.readString(output.resolve("runs.jsonl"))).isEqualTo(before);
            assertThat(SearchEvaluationCli.run(new String[]{"--modes", "baseline"}, sink, sink)).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test
    void cliRejectsIncomparableOrAmbiguousOptions() {
        for (String[] args : List.of(
                new String[]{"--base-url", "http://localhost:8080/api"},
                new String[]{"--base-url", "http://localhost:8080", "--modes", "baseline,baseline"},
                new String[]{"--base-url", "http://localhost:8080", "--modes", "suggestion_accepted"},
                new String[]{"--base-url", "http://localhost:8080", "--warmup", "-1"},
                new String[]{"--base-url", "http://localhost:8080", "--timeout-ms", "0"})) {
            assertThatThrownBy(() -> SearchEvaluationCli.parse(args)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void customScenariosCannotMixDevelopmentAndFinalWithinAnIntentGroup() throws Exception {
        Path file = temp.resolve("mixed-split.json");
        String scenarios = Files.readString(Path.of("docs/search-scenarios-30.json"));
        // Q001 and Q016 share sweat_all. Moving Q001 alone would leak this intent across splits.
        Files.writeString(file, scenarios.replaceFirst("\"split\": \"development\"", "\"split\": \"final\""));
        var defaults = options("mixed", 0, false);
        var options = new SearchEvaluator.Options(defaults.baseUrl(), defaults.output(), defaults.modes(), 0,
                false, defaults.timeout(), defaults.catalog(), file);
        assertThatThrownBy(() -> new SearchEvaluator(options, (uri, timeout) -> {
            throw new AssertionError("Invalid datasets must fail before HTTP");
        }).run()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only development");
        assertThat(options.output()).doesNotExist();
    }

    private SearchEvaluator.Options options(String name, int warmup, boolean accept) {
        return new SearchEvaluator.Options(URI.create("http://localhost:8080"), temp.resolve(name), List.of("baseline"),
                warmup, accept, Duration.ofSeconds(1), Path.of("docs/catalog-50.json"), Path.of("docs/search-scenarios-30.json"));
    }

    private Path singleScenario(String filter, Object value) throws IOException {
        var scenario = map("id", "one", "intent_group", "one", "split", "development",
                "request", map("q", "맨투맨", "page", 1, "size", 10, "filters", map(filter, value)),
                "expected", map("relevant_product_ids", List.of("P001"), "expected_zero_results", false,
                        "correction", map("policy", "no_spelling_suggestion", "auto_apply", false, "acceptable_suggestions", List.of())));
        Path file = temp.resolve("scenario-" + filter + ".json");
        Files.writeString(file, JSON.writeValueAsString(map("dataset_id", "test-only", "catalog_id", "catalog-v0.1", "cases", List.of(scenario))));
        return file;
    }

    private static JsonNode summary(SearchEvaluator.Options options) throws IOException {
        return JSON.readTree(Files.readString(options.output().resolve("summary.json")));
    }

    private static SearchEvaluator.Scenario scenario(Set<String> relevant, boolean zero) {
        return new SearchEvaluator.Scenario("test", "q", 1, 10, Map.of(), relevant, zero, false, Set.of());
    }

    private static SearchEvaluator.Response response(List<String> ids) {
        return new SearchEvaluator.Response("r", "q", "q", List.of(), ids, ids.size(), 1, "c", "i");
    }

    private static SearchEvaluator.Attempt attempt(SearchEvaluator.Scenario scenario, SearchEvaluator.Response response) {
        return new SearchEvaluator.Attempt(scenario, "baseline", "original", false, response, null, 1);
    }

    private static SearchEvaluator.WireResponse wire(URI uri, List<String> ids, List<String> suggestions, double elapsed) {
        return new SearchEvaluator.WireResponse(200, JSON.writeValueAsString(body(parameters(uri).get("q"), ids, suggestions)), elapsed);
    }

    private static Map<String, Object> body(String query, List<String> ids, List<String> suggestions) {
        return map("request_id", "request-" + query, "original_query", query, "executed_query", query,
                "suggestions", suggestions, "items", ids.stream().map(id -> Map.of("id", id)).toList(), "total", ids.size(),
                "timing_ms", 0.5, "config_version", "config-v1", "index_version", "index-v1");
    }

    private static Map<String, String> parameters(URI uri) {
        Map<String, String> decoded = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            decoded.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return decoded;
    }
}

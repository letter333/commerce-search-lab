package org.letter33.commercesearchlab.evaluation;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone test-runtime entry point; does not start Spring or a search service. */
public final class SearchEvaluationCli {
    private SearchEvaluationCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (List.of(args).contains("--help")) {
                out.println("""
                        Search evaluation against an already running service:
                          --base-url http://localhost:8080  (required; origin only)
                          --output build/evaluations/<new-directory>
                          --modes baseline,query_v1,ranking_v1  (default: baseline)
                          --warmup 1           (full scenario passes per mode; excluded from metrics)
                          --accept-suggestions (explicitly execute the actual first suggestion)
                          --timeout-ms 5000
                          --catalog docs/catalog-50.json
                          --scenarios docs/search-scenarios-30.json
                        Exit: 0 complete, 1 request/response failure, 2 input or filesystem error.
                        Existing output directories are never overwritten. This is not a load test.
                        """);
                return 0;
            }
            var options = parse(args);
            var report = new SearchEvaluator(options).run();
            out.println("Evaluation " + (report.valid() ? "complete" : "FAILED") + ": "
                    + options.output().toAbsolutePath());
            return report.valid() ? 0 : 1;
        } catch (Exception e) {
            err.println("Evaluation could not run: " + e.getMessage());
            return 2;
        }
    }

    static SearchEvaluator.Options parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        boolean accept = false;
        Set<String> allowed = Set.of("--base-url", "--output", "--modes", "--warmup",
                "--timeout-ms", "--catalog", "--scenarios");
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--accept-suggestions")) {
                if (accept) throw new IllegalArgumentException("Duplicate option: " + option);
                accept = true;
            } else {
                if (!allowed.contains(option)) throw new IllegalArgumentException("Unknown option: " + option);
                if (i + 1 == args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + option);
                }
                if (values.put(option, args[++i]) != null) {
                    throw new IllegalArgumentException("Duplicate option: " + option);
                }
            }
        }
        if (!values.containsKey("--base-url")) throw new IllegalArgumentException("--base-url is required");
        URI base = URI.create(values.get("--base-url"));
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("--base-url must be an http(s) origin, without credentials, query or path");
        }
        List<String> modes = new ArrayList<>();
        for (String mode : values.getOrDefault("--modes", "baseline").split(",", -1)) {
            if (!Set.of("baseline", "query_v1", "ranking_v1").contains(mode) || modes.contains(mode)) {
                throw new IllegalArgumentException("Unsupported or duplicate mode: " + mode);
            }
            modes.add(mode);
        }
        int warmup = Integer.parseInt(values.getOrDefault("--warmup", "1"));
        int timeout = Integer.parseInt(values.getOrDefault("--timeout-ms", "5000"));
        if (warmup < 0 || timeout <= 0) throw new IllegalArgumentException("warmup must be >= 0 and timeout-ms > 0");
        String timestamp = Instant.now().toString().replace(":", "-");
        return new SearchEvaluator.Options(base, Path.of(values.getOrDefault("--output", "build/evaluations/" + timestamp)),
                List.copyOf(modes), warmup, accept, Duration.ofMillis(timeout),
                Path.of(values.getOrDefault("--catalog", "docs/catalog-50.json")),
                Path.of(values.getOrDefault("--scenarios", "docs/search-scenarios-30.json")));
    }
}

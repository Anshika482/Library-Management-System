package com.library.lms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps the deployment artifacts consistent with the configuration they deploy.
 *
 * <p>Facts, not wording: the variables the configuration reads, the images and
 * user the Dockerfile uses, what the build context leaves out, and what CI
 * runs. The README is checked only for names that must appear in it - every
 * variable, every migration file and the public endpoints.</p>
 *
 * <p>Paths are relative to {@code backend/}, the directory Maven runs the tests
 * from.</p>
 */
class DeploymentArtifactsTest {

    private static final Path BACKEND = Path.of("").toAbsolutePath();

    private static final Path REPOSITORY = BACKEND.getParent();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?::[^}]*)?}");

    /** Settings that must only ever be supplied when the application starts. */
    private static final List<String> RUNTIME_ONLY =
            List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "JWT_ISSUER", "JWT_AUDIENCE");

    // ---------- environment variables ----------

    @Test
    void everyVariableTheConfigurationReadsIsInTheTemplateAndTheReadme() throws IOException {
        Set<String> variables = configuredVariables();
        assertThat(variables).contains("DB_URL", "DB_PASSWORD", "JWT_SECRET", "APP_TIME_ZONE");

        assertThat(envTemplate().keySet()).containsAll(variables);

        String readme = read(REPOSITORY.resolve("README.md"));
        assertThat(variables).allSatisfy(variable -> assertThat(readme).contains(variable));
    }

    @Test
    void theEnvTemplateHoldsPlaceholdersOnly() throws IOException {
        Map<String, String> template = envTemplate();

        template.forEach((key, value) -> {
            // Credentials: DB_PASSWORD, BOOTSTRAP_ADMIN_PASSWORD, JWT_SECRET. "Ends in
            // PASSWORD" rather than "contains" it, so a setting that is merely about
            // passwords - PASSWORD_RESET_TOKEN_VALIDITY, a lifetime - may show its default.
            if (key.contains("SECRET") || key.endsWith("PASSWORD") || key.equals("DB_USERNAME")) {
                assertThat(value).as("%s must be left blank", key).isEmpty();
            }
            assertThat(value.toLowerCase()).as(key).doesNotContain("password=").doesNotContain("user=");
        });

        assertThat(template.get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
        assertThat(template.get("DB_URL")).as("TLS is required in production").containsIgnoringCase("sslMode=");
        assertThat(template.get("APP_TIME_ZONE")).isEqualTo("Asia/Kolkata");
    }

    // ---------- Docker ----------

    @Test
    void theImageRunsOnlyTheJreAsANonRootUserInTheBusinessZone() throws IOException {
        List<String> instructions = dockerInstructions();
        List<String> froms = instructions.stream().filter(line -> line.startsWith("FROM ")).toList();

        assertThat(froms).hasSizeGreaterThanOrEqualTo(2);
        assertThat(froms.get(0)).contains("eclipse-temurin:21-jdk");
        assertThat(froms.get(froms.size() - 1)).contains("eclipse-temurin:21-jre");

        List<String> runtime = instructions.subList(instructions.lastIndexOf(froms.get(froms.size() - 1)),
                instructions.size());

        assertThat(runtime)
                .as("the runtime stage copies only from the build stage")
                .filteredOn(line -> line.startsWith("COPY "))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("--from=build"));

        List<String> users = runtime.stream().filter(line -> line.startsWith("USER ")).toList();
        assertThat(users).isNotEmpty();
        assertThat(users.get(users.size() - 1).substring("USER ".length()).trim())
                .as("not root")
                .isNotIn("root", "0", "0:0");

        String environment = String.join(" ", runtime.stream().filter(line -> line.startsWith("ENV ")).toList());
        assertThat(environment).contains("SPRING_PROFILES_ACTIVE=prod").contains("APP_TIME_ZONE=Asia/Kolkata");

        assertThat(runtime)
                .filteredOn(line -> line.startsWith("ENTRYPOINT "))
                .singleElement()
                .asString()
                .contains("-Duser.timezone=Asia/Kolkata");

        assertThat(instructions)
                .as("no secret or database setting is baked into the image")
                .filteredOn(line -> line.startsWith("ENV ") || line.startsWith("ARG "))
                .allSatisfy(line -> RUNTIME_ONLY.forEach(name -> assertThat(line).doesNotContain(name)));
    }

    @Test
    void theBuildContextLeavesOutBuildOutputAndEnvironmentFiles() throws IOException {
        List<String> patterns = read(BACKEND.resolve(".dockerignore")).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertThat(patterns).contains("target/", ".env");
    }

    // ---------- CI ----------

    @Test
    void ciTestsOnJava21AgainstMysql8AndThenSmokeTestsTheImage() throws IOException {
        String text = read(REPOSITORY.resolve(".github/workflows/backend-ci.yml"));
        Map<?, ?> jobs = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(text)).get("jobs");

        String testJob = null;
        for (Map.Entry<?, ?> job : jobs.entrySet()) {
            if (runScripts((Map<?, ?>) job.getValue()).contains("./mvnw -B -ntp test")) {
                testJob = (String) job.getKey();
            }
        }
        assertThat(testJob).as("a job runs the test suite with the wrapper").isNotNull();

        Map<?, ?> test = (Map<?, ?>) jobs.get(testJob);
        assertThat(((Map<?, ?>) test.get("services")).values())
                .as("a MySQL 8 service")
                .anySatisfy(service -> assertThat(String.valueOf(((Map<?, ?>) service).get("image")))
                        .startsWith("mysql:8"));
        assertThat(steps(test))
                .as("Java 21")
                .anySatisfy(step -> {
                    assertThat(String.valueOf(step.get("uses"))).startsWith("actions/setup-java");
                    assertThat(String.valueOf(((Map<?, ?>) step.get("with")).get("java-version"))).isEqualTo("21");
                });

        String finalTestJob = testJob;
        Map<?, ?> smoke = jobs.values().stream()
                .map(job -> (Map<?, ?>) job)
                .filter(job -> String.valueOf(job.get("needs")).contains(finalTestJob))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no job runs after the tests"));
        assertThat(runScripts(smoke))
                .contains("docker build", "/actuator/health/readiness", "/actuator/health/liveness", "/api/books")
                .contains("401");

        assertThat(text).as("CI uses only throwaway, test-only values").doesNotContain("secrets.");
    }

    // ---------- README ----------

    @Test
    void theReadmeNamesEveryMigrationAndThePublicEndpoints() throws IOException {
        String readme = read(REPOSITORY.resolve("README.md"));

        List<String> migrations;
        try (Stream<Path> files = Files.list(BACKEND.resolve("src/main/resources/db/migration"))) {
            migrations = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .toList();
        }
        assertThat(migrations).isNotEmpty().allSatisfy(migration -> assertThat(readme).contains(migration));

        assertThat(readme).contains("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                "/actuator/health/liveness", "/actuator/health/readiness");
    }

    // ---------- helpers ----------

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s exists", path).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Every environment variable named by a placeholder on a non-comment line of the shipped configuration. */
    private static Set<String> configuredVariables() throws IOException {
        Set<String> names = new TreeSet<>();
        for (String file : List.of("application.properties", "application-prod.properties")) {
            for (String line : read(BACKEND.resolve("src/main/resources").resolve(file)).lines().toList()) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                Matcher placeholder = PLACEHOLDER.matcher(line);
                while (placeholder.find()) {
                    names.add(placeholder.group(1));
                }
            }
        }
        return names;
    }

    private static Map<String, String> envTemplate() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : read(BACKEND.resolve(".env.example")).lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            assertThat(equals).as("KEY=VALUE: %s", trimmed).isPositive();
            values.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        return values;
    }

    /** The Dockerfile's instructions, one per entry, with continuation lines joined and comments dropped. */
    private static List<String> dockerInstructions() throws IOException {
        List<String> instructions = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : read(BACKEND.resolve("Dockerfile")).lines().toList()) {
            String trimmed = line.trim();
            if (current.isEmpty() && (trimmed.isEmpty() || trimmed.startsWith("#"))) {
                continue;
            }
            if (trimmed.endsWith("\\")) {
                current.append(trimmed, 0, trimmed.length() - 1).append(' ');
                continue;
            }
            current.append(trimmed);
            instructions.add(current.toString());
            current.setLength(0);
        }
        return instructions;
    }

    private static List<Map<?, ?>> steps(Map<?, ?> job) {
        List<Map<?, ?>> steps = new ArrayList<>();
        for (Object step : (List<?>) job.get("steps")) {
            steps.add((Map<?, ?>) step);
        }
        return steps;
    }

    private static String runScripts(Map<?, ?> job) {
        StringBuilder scripts = new StringBuilder();
        for (Map<?, ?> step : steps(job)) {
            if (step.get("run") != null) {
                scripts.append(step.get("run")).append('\n');
            }
        }
        return scripts.toString();
    }
}

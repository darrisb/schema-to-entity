package com.example.dynamicmeta.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CN-009: the API image builds the console and puts the bundle in the jar.
 *
 * <p>The Spring tests run against the fixture bundle on the test classpath, so
 * they say nothing about the image half of the story. These are plain file
 * assertions in their own class (no application context) and they pin what the
 * console stage needs: the vendor tarballs before {@code npm ci} resolves the
 * {@code file:} dependencies, {@code npm ci} before the build, and the built
 * bundle before the demo jar is packaged. Dropping or reordering one of those
 * instructions fails here, even though the image would still build.
 *
 * <p>Patterns allow any whitespace and any destination spelling, and comment
 * lines are ignored, so a behaviour-preserving edit (an extra goal, a trailing
 * slash, a reworded comment) keeps passing.
 */
class ConsoleDockerfileTests {

    private static final Pattern CONSOLE_STAGE = Pattern.compile(
            "FROM\\s+\\S*node:\\d+\\S*\\s+AS\\s+console", Pattern.CASE_INSENSITIVE);

    private static final Pattern VENDOR_COPY = Pattern.compile(
            "COPY\\s+\\S*console/vendor/?\\s+\\S*vendor/?\\S*");

    private static final Pattern NPM_INSTALL = Pattern.compile("npm\\s+ci\\b");

    private static final Pattern NPM_BUILD = Pattern.compile("npm\\s+run\\s+build\\b");

    private static final Pattern BUNDLE_COPY = Pattern.compile(
            "COPY\\s+--from=console\\s+\\S*console/dist\\S*\\s+\\S*static/console");

    private static final Pattern DEMO_PACKAGE = Pattern.compile(
            "mvn\\b[^\\n]*demo/pom\\.xml[^\\n]*\\bpackage\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void theConsoleStageInstallsDependenciesBeforeBuilding() {
        String dockerfile = dockerfile();

        int stage = position(dockerfile, "a node console stage", CONSOLE_STAGE);
        int vendor = position(dockerfile, "the console/vendor tarballs", VENDOR_COPY);
        int install = position(dockerfile, "npm ci", NPM_INSTALL);
        int build = position(dockerfile, "npm run build", NPM_BUILD);

        assertThat(vendor)
                .as("the vendor COPY is in the console stage")
                .isGreaterThan(stage);
        assertThat(install)
                .as("npm ci needs the file: tarballs from console/vendor/")
                .isGreaterThan(vendor);
        assertThat(build)
                .as("the console build needs the modules npm ci installed")
                .isGreaterThan(install);
    }

    @Test
    void theBuiltBundleIsCopiedIntoTheJarBeforePackaging() {
        String dockerfile = dockerfile();

        int bundle = position(dockerfile,
                "the console bundle copied into the demo static resources", BUNDLE_COPY);
        int packageGoal = position(dockerfile, "the demo jar build", DEMO_PACKAGE);

        assertThat(packageGoal)
                .as("the bundle has to exist before the jar is packaged")
                .isGreaterThan(bundle);
    }

    /**
     * Reads the repository Dockerfile, found by walking up from the working
     * directory so an IDE runner that starts at the repository root works like
     * Maven starting in demo/. The repository root is the first directory
     * holding both a Dockerfile and demo/pom.xml; when none is reachable the
     * test is skipped, not failed. Comments are dropped so a pattern matches an
     * instruction and never prose that mentions it.
     */
    private static String dockerfile() {
        Path dockerfile = findRepositoryDockerfile();
        Assumptions.assumeTrue(dockerfile != null,
                "no repository Dockerfile above " + System.getProperty("user.dir"));
        try {
            return withoutComments(Files.readString(dockerfile));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dockerfile, e);
        }
    }

    private static Path findRepositoryDockerfile() {
        for (Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
                dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("Dockerfile");
            if (Files.isRegularFile(candidate)
                    && Files.isRegularFile(dir.resolve("demo").resolve("pom.xml"))) {
                return candidate;
            }
        }
        return null;
    }

    private static String withoutComments(String dockerfile) {
        return dockerfile.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    /** Where the pattern first matches, so the tests can compare positions. */
    private static int position(String dockerfile, String what, Pattern pattern) {
        Matcher matcher = pattern.matcher(dockerfile);
        assertThat(matcher.find())
                .as("Dockerfile contains %s", what)
                .isTrue();
        return matcher.start();
    }
}

package io.apitomy.common.apps.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.SelectorUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Validates an offline Maven repository by resolving the full transitive dependency tree of each
 * matching artifact and checking that all compile/runtime dependencies are productized for the
 * configured {@code productizationModes} (by default {@code ibm}, i.e. a {@code -ibm-} or
 * {@code .ibm-} version suffix).
 *
 * <p>For each artifact matching the configured include patterns, this mojo uses Maven's dependency
 * resolver (Aether) to build the complete transitive dependency tree — exactly as Maven would
 * resolve it during a real build. This means all scope transitivity rules, optional dependency
 * exclusion, BOM imports, property resolution, and conflict resolution are handled correctly by
 * Maven itself.</p>
 *
 * <p>This mojo can operate against either a repository directory or a repository .zip file. When
 * using a .zip file, the archive may optionally contain an extra root directory wrapping the
 * repository contents.</p>
 */
@Mojo(name = "verify-maven-repository")
public class VerifyMavenRepositoryMojo extends AbstractVerifyMojo {

    /** Directory name that explicitly marks the repository root inside an archive. */
    private static final String MARKER_DIRECTORY_NAME = "maven-repository";

    /** How many wrapping directories an archive may nest the repository in. */
    private static final int MAX_WRAPPER_DEPTH = 5;

    /** How many POMs to examine before giving up on deriving the root from the layout. */
    private static final int MAX_POMS_SAMPLED_FOR_ROOT_DETECTION = 50;

    /**
     * Path to an offline Maven repository directory to validate.
     * Either this or {@code repositoryZip} must be configured, but not both.
     */
    @Parameter(property = "repositoryDirectory")
    File repositoryDirectory;

    /**
     * Path to a .zip file containing an offline Maven repository to validate.
     * The zip may optionally have an extra root directory wrapping the repository contents.
     * Either this or {@code repositoryDirectory} must be configured, but not both.
     */
    @Parameter(property = "repositoryZip")
    File repositoryZip;

    /**
     * List of patterns to match artifact IDs to validate (e.g. {@code apitomy-*}).
     * Only artifacts whose artifactId matches at least one of these patterns will have their
     * full transitive dependency tree resolved and validated. Supports wildcard patterns.
     */
    @Parameter(property = "artifactIncludes", required = true)
    List<String> artifactIncludes;

    /**
     * List of patterns to exclude artifact IDs from validation (e.g. {@code *-parent}).
     * Takes precedence over {@code artifactIncludes}. Supports wildcard patterns.
     */
    @Parameter(property = "artifactExcludes")
    List<String> artifactExcludes;

    @SuppressWarnings("deprecation")
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * Coordinates of an artifact found in the repository.
     */
    static class ArtifactCoordinates {

        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String packaging;

        /**
         * Creates a new instance of ArtifactCoordinates.
         *
         * @param groupId the Maven groupId
         * @param artifactId the Maven artifactId
         * @param version the Maven version
         * @param packaging the Maven packaging type
         */
        ArtifactCoordinates(String groupId, String artifactId, String version,
                String packaging) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.packaging = packaging;
        }

        /**
         * @return the Maven groupId
         */
        String groupId() {
            return groupId;
        }

        /**
         * @return the Maven artifactId
         */
        String artifactId() {
            return artifactId;
        }

        /**
         * @return the Maven version
         */
        String version() {
            return version;
        }

        /**
         * @return the Maven packaging type
         */
        String packaging() {
            return packaging;
        }

        /**
         * Returns the GAV string representation.
         *
         * @return the GAV string in the format "groupId:artifactId:version"
         */
        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path tempExtractDir = null;
        Path tempLocalRepo = null;

        try {
            validateProductizationModes();
            validateConfiguration();

            if (artifactExcludes == null) {
                artifactExcludes = List.of();
            }
            if (ignoreGAVs == null) {
                ignoreGAVs = List.of();
            }

            // Phase 1: Get the repository directory (extract zip if needed).
            File repoDir;
            if (repositoryDirectory != null) {
                repoDir = repositoryDirectory;
                getLog().info("Using repository directory: " + repoDir.getAbsolutePath());
            } else {
                getLog().info("Extracting repository zip: " + repositoryZip.getAbsolutePath());
                tempExtractDir = Files.createTempDirectory("verify-repo-extract-");
                extractZip(repositoryZip, tempExtractDir);
                repoDir = detectRepoRoot(tempExtractDir).toFile();
                getLog().info("Repository root: " + repoDir.getAbsolutePath());
            }

            // Phase 2: Scan for all POM files and find matching artifacts.
            List<ArtifactCoordinates> allArtifacts = scanRepository(repoDir);
            getLog().info("Found " + allArtifacts.size() + " total artifacts in repository.");

            List<ArtifactCoordinates> matchingArtifacts = allArtifacts.stream()
                    .filter(this::matchesIncludes)
                    .filter(a -> !matchesExcludes(a))
                    .collect(Collectors.toList());

            getLog().info("Found " + matchingArtifacts.size()
                    + " artifacts matching include patterns.");

            if (matchingArtifacts.isEmpty()) {
                throw new MojoFailureException(
                        "No artifacts matched the configured include patterns: "
                                + artifactIncludes);
            }

            // Phase 3: Set up Aether with the offline repo as the sole remote repository
            // and a clean temp local repo so nothing is pre-cached.
            tempLocalRepo = Files.createTempDirectory("verify-repo-local-");
            RemoteRepository offlineRepo = new RemoteRepository.Builder(
                    "offline-repo", "default", repoDir.toURI().toString()).build();

            DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(
                    repoSession);
            session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(
                    session, new LocalRepository(tempLocalRepo.toFile())));

            // Phase 4: For each matching artifact, resolve the dependency tree and validate.
            Set<String> unproductizedDependencies = new TreeSet<>();
            Set<String> unproductizedGavs = new TreeSet<>();
            Set<String> resolutionErrors = new TreeSet<>();
            int artifactsChecked = 0;

            for (ArtifactCoordinates coords : matchingArtifacts) {
                artifactsChecked++;
                logVerbose("Resolving dependency tree for: " + coords.gav());

                CollectResult result = collectDependencyTree(session, offlineRepo, coords);

                if (result == null) {
                    resolutionErrors.add(coords.gav()
                            + " (failed to collect dependency tree)");
                    continue;
                }

                // Report any collection exceptions as resolution errors.
                if (!result.getExceptions().isEmpty()) {
                    for (Exception ex : result.getExceptions()) {
                        resolutionErrors.add(coords.gav() + ": " + ex.getMessage());
                    }
                }

                // Walk the dependency tree and check each dependency's version.
                if (result.getRoot() != null) {
                    logVerboseDependencyTree(result.getRoot());
                    validateDependencyTree(result.getRoot(), coords.gav(),
                            unproductizedDependencies, unproductizedGavs);
                }
            }

            // Phase 5: Write CSV report (before reportResults, which may throw).
            writeCsvReport(unproductizedGavs);

            // Phase 6: Report results.
            getLog().info("=== Maven Repository Verification Results ===");
            getLog().info("  Artifacts checked:          " + artifactsChecked);
            reportResults(unproductizedDependencies, resolutionErrors);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error verifying maven repository", e);
        } finally {
            cleanupTempDir(tempExtractDir);
            cleanupTempDir(tempLocalRepo);
        }
    }

    /**
     * Collects the transitive dependency tree for an artifact using Maven's Aether resolver.
     *
     * @param session the repository session configured with a temp local repo
     * @param offlineRepo the remote repository pointing to the offline Maven repo
     * @param coords the artifact coordinates to resolve
     * @return the collect result containing the dependency tree, or null on complete failure
     */
    private CollectResult collectDependencyTree(RepositorySystemSession session,
            RemoteRepository offlineRepo, ArtifactCoordinates coords) {
        try {
            String extension = "pom".equals(coords.packaging()) ? "pom" : "jar";
            CollectRequest request = new CollectRequest();
            request.setRoot(new Dependency(
                    new DefaultArtifact(coords.groupId(), coords.artifactId(),
                            extension, coords.version()),
                    "compile"));
            request.addRepository(offlineRepo);

            return repositorySystem.collectDependencies(session, request);

        } catch (DependencyCollectionException e) {
            getLog().warn("Dependency collection errors for " + coords.gav()
                    + ": " + e.getMessage());
            // Return the partial result so we can still validate what was resolved.
            return e.getResult();
        }
    }

    /**
     * Validates the mojo configuration parameters.
     *
     * @throws MojoFailureException if the configuration is invalid
     */
    private void validateConfiguration() throws MojoFailureException {
        if (repositoryDirectory == null && repositoryZip == null) {
            throw new MojoFailureException(
                    "Either 'repositoryDirectory' or 'repositoryZip' must be configured.");
        }
        if (repositoryDirectory != null && repositoryZip != null) {
            throw new MojoFailureException(
                    "Only one of 'repositoryDirectory' or 'repositoryZip' can be configured.");
        }
        if (repositoryDirectory != null && !repositoryDirectory.isDirectory()) {
            throw new MojoFailureException(
                    "Configured repositoryDirectory is not a directory: " + repositoryDirectory);
        }
        if (repositoryZip != null && (!repositoryZip.isFile()
                || !repositoryZip.getName().endsWith(".zip"))) {
            throw new MojoFailureException(
                    "Configured repositoryZip is not a valid zip file: " + repositoryZip);
        }
        if (artifactIncludes == null || artifactIncludes.isEmpty()) {
            throw new MojoFailureException(
                    "'artifactIncludes' must be configured with at least one pattern.");
        }
    }

    /**
     * Extracts a zip file to a destination directory, guarding against zip-slip path traversal.
     *
     * @param zipFile the zip file to extract
     * @param destDir the destination directory
     * @throws IOException if an I/O error occurs or a zip-slip attack is detected
     */
    void extractZip(File zipFile, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = destDir.resolve(entry.getName()).normalize();

                // Guard against zip-slip path traversal.
                if (!entryPath.startsWith(destDir)) {
                    throw new IOException(
                            "Zip entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, entryPath);
                    }
                }
            }
        }
    }

    /**
     * Detects the actual repository root after zip extraction, where the archive may wrap the
     * repository in one or more extra directories.
     *
     * <p>The root is derived from the layout of an actual artifact: a POM's own coordinates say
     * exactly how deep below the root it must sit, so stripping {@code groupId/artifactId/version}
     * from its location yields the root. That beats guessing from directory shape, because a
     * repository holding a single top-level group (say, only {@code io/apitomy/...}) looks
     * exactly like a wrapped one.
     *
     * <p>When no POM anchors the search — an empty or non-repository archive — the previous
     * heuristics apply: a directory named {@code maven-repository}, else drilling down through
     * single-child directories.
     *
     * @param extractDir the directory where the zip was extracted
     * @return the detected repository root path
     * @throws IOException if an I/O error occurs
     */
    Path detectRepoRoot(Path extractDir) throws IOException {
        Path derived = deriveRepoRootFromPomLayout(extractDir);
        if (derived != null) {
            logVerbose("Derived repository root from artifact layout: " + derived);
            return derived;
        }

        Path marker = findMarkerDirectory(extractDir);
        if (marker != null) {
            logVerbose("Found maven-repository directory: " + marker);
            return marker;
        }

        Path current = drillThroughSingleSubdirectories(extractDir);
        logVerbose("Using repository root: " + current);
        return current;
    }

    /**
     * Derives the repository root from the location of a POM, by stripping the
     * {@code groupId/artifactId/version} path that Maven's layout requires below the root.
     * POMs whose coordinates do not match their own location (an inherited groupId, say) are
     * skipped, and only the first handful are examined so that a large repository is not walked
     * in full.
     *
     * @param extractDir the directory where the zip was extracted
     * @return the derived repository root, or {@code null} if no POM anchors it
     * @throws IOException if an I/O error occurs
     */
    private Path deriveRepoRootFromPomLayout(Path extractDir) throws IOException {
        try (Stream<Path> files = Files.walk(extractDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".pom"))
                    .limit(MAX_POMS_SAMPLED_FOR_ROOT_DETECTION)
                    .map(this::repoRootForPom)
                    .filter(root -> root != null && root.startsWith(extractDir))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Walks up from a POM file through the path segments its own coordinates imply.
     *
     * @param pomFile the POM file to anchor on
     * @return the repository root the POM implies, or {@code null} if its location does not
     *         match its coordinates
     */
    private Path repoRootForPom(Path pomFile) {
        ArtifactCoordinates coords = readCoordinates(pomFile);
        if (coords == null) {
            return null;
        }

        List<String> expectedSegments = new ArrayList<>(
                Arrays.asList(coords.groupId().split("\\.")));
        expectedSegments.add(coords.artifactId());
        expectedSegments.add(coords.version());

        Path cursor = pomFile.getParent();
        for (int i = expectedSegments.size() - 1; i >= 0; i--) {
            if (cursor == null || cursor.getFileName() == null
                    || !cursor.getFileName().toString().equals(expectedSegments.get(i))) {
                logVerbose("POM location does not match its coordinates, skipping: " + pomFile);
                return null;
            }
            cursor = cursor.getParent();
        }
        return cursor;
    }

    /**
     * Looks for a directory named {@code maven-repository}, descending through single-child
     * directories as the only candidates for a wrapper.
     *
     * @param extractDir the directory where the zip was extracted
     * @return the marker directory, or {@code null} if there is none
     * @throws IOException if an I/O error occurs
     */
    private Path findMarkerDirectory(Path extractDir) throws IOException {
        Path current = extractDir;

        for (int depth = 0; depth < MAX_WRAPPER_DEPTH; depth++) {
            List<Path> subdirs = subdirectoriesOf(current);
            for (Path subdir : subdirs) {
                if (subdir.getFileName().toString().equals(MARKER_DIRECTORY_NAME)) {
                    return subdir;
                }
            }
            if (subdirs.size() != 1) {
                return null;
            }
            current = subdirs.get(0);
        }
        return null;
    }

    /**
     * Drills down through single-child directories, the shape an archive takes when it wraps the
     * repository in one or more extra directories.
     *
     * @param extractDir the directory where the zip was extracted
     * @return the innermost directory reached
     * @throws IOException if an I/O error occurs
     */
    private Path drillThroughSingleSubdirectories(Path extractDir) throws IOException {
        Path current = extractDir;

        for (int depth = 0; depth < MAX_WRAPPER_DEPTH; depth++) {
            // A single subdirectory (possibly with non-directory files like README.md
            // alongside it) is a wrapper; anything else is the root.
            List<Path> subdirs = subdirectoriesOf(current);
            if (subdirs.size() != 1) {
                return current;
            }
            logVerbose("Drilling into single subdirectory: " + subdirs.get(0).getFileName());
            current = subdirs.get(0);
        }
        return current;
    }

    /**
     * Lists the subdirectories of a directory.
     *
     * @param dir the directory to list
     * @return its subdirectories
     * @throws IOException if an I/O error occurs
     */
    private List<Path> subdirectoriesOf(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    /**
     * Scans a Maven repository directory for all POM files and extracts their GAV coordinates.
     *
     * @param repoDir the root directory of the Maven repository
     * @return the list of artifact coordinates found
     * @throws IOException if an I/O error occurs
     */
    List<ArtifactCoordinates> scanRepository(File repoDir) throws IOException {
        List<ArtifactCoordinates> artifacts = new ArrayList<>();

        Files.walkFileTree(repoDir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".pom")) {
                    ArtifactCoordinates coords = readCoordinates(file);
                    if (coords != null) {
                        artifacts.add(coords);
                        logVerbose("Found artifact: " + coords.gav()
                                + " (" + coords.packaging() + ")");
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return artifacts;
    }

    /**
     * Reads the coordinates declared by a POM file, falling back to the parent declaration for
     * an inherited groupId or version.
     *
     * @param pomFile the POM file to read
     * @return the coordinates, or {@code null} if the POM cannot be parsed or is incomplete
     */
    private ArtifactCoordinates readCoordinates(Path pomFile) {
        try (InputStream is = Files.newInputStream(pomFile)) {
            Model model = new MavenXpp3Reader().read(is);
            String groupId = model.getGroupId() != null ? model.getGroupId()
                    : (model.getParent() != null ? model.getParent().getGroupId() : null);
            String version = model.getVersion() != null ? model.getVersion()
                    : (model.getParent() != null ? model.getParent().getVersion() : null);
            String artifactId = model.getArtifactId();
            String packaging = model.getPackaging() != null ? model.getPackaging() : "jar";

            if (groupId == null || artifactId == null || version == null) {
                getLog().warn("Skipping POM with incomplete GAV: " + pomFile);
                return null;
            }
            return new ArtifactCoordinates(groupId, artifactId, version, packaging);

        } catch (Exception e) {
            getLog().warn("Failed to parse POM: " + pomFile + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks whether an artifact's artifactId matches any of the configured include patterns.
     *
     * @param coords the artifact coordinates to check
     * @return true if the artifact matches at least one include pattern
     */
    boolean matchesIncludes(ArtifactCoordinates coords) {
        for (String pattern : artifactIncludes) {
            if (SelectorUtils.match(pattern, coords.artifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether an artifact's artifactId matches any of the configured exclude patterns.
     *
     * @param coords the artifact coordinates to check
     * @return true if the artifact matches at least one exclude pattern
     */
    boolean matchesExcludes(ArtifactCoordinates coords) {
        for (String pattern : artifactExcludes) {
            if (SelectorUtils.match(pattern, coords.artifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively deletes a temporary directory if it exists.
     *
     * @param dir the directory to delete
     */
    private void cleanupTempDir(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc)
                        throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            getLog().warn("Failed to clean up temp directory: " + dir + " - " + e.getMessage());
        }
    }

}

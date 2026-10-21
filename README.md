[![Verify Build Workflow](https://github.com/Apitomy/apitomy-maven-plugin/actions/workflows/verify.yaml/badge.svg)](https://github.com/Apitomy/apitomy-maven-plugin/actions/workflows/verify.yaml?query=branch%3Amain)

# Apitomy Maven Plugin

A collection of Maven plugin goals used by Apitomy projects, primarily for validating that
dependencies in productized builds are properly aligned. Both IBM and Red Hat productization
are supported; see [Productization Check](#productization-check).

### Goals Overview

| Goal | Description |
|------|-------------|
| `merge` | Merges multiple Java `.properties` files into a single output file. |
| `verify-dependencies` | Validates that files (JARs, etc.) in directories or zip distributions are productized. |
| `verify-maven-repository` | Validates an offline Maven repository by resolving full dependency trees. |
| `verify-project-dependencies` | Collects and checks all dependencies of the current Maven project, writing the result to a file. Reports warnings only — it never fails the build. |
| `verify-project-dependencies-report` | Aggregates the results written by `verify-project-dependencies` across the reactor and **fails the build** if anything is unaligned. |
| `verify-artifact-dependencies` | Validates that all dependencies of a single Maven artifact are productized. |

---

### `merge` — Merge Properties Files

Merges multiple Java `.properties` files into a single output file. Optionally deletes the
input files after merging.

#### Configuration

```xml
<plugin>
    <groupId>io.apitomy</groupId>
    <artifactId>apitomy-maven-plugin</artifactId>
    <version>${apitomy-maven-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>merge</goal>
            </goals>
            <configuration>
                <output>${project.build.directory}/merged.properties</output>
                <inputs>
                    <input>${project.basedir}/src/main/resources/a.properties</input>
                    <input>${project.basedir}/src/main/resources/b.properties</input>
                </inputs>
                <deleteInputs>false</deleteInputs>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `output` | Yes | — | Path to the merged output properties file. |
| `inputs` | Yes | — | List of input properties files to merge. |
| `deleteInputs` | No | `false` | Delete input files after merging. |

---

### `verify-dependencies` — Verify Files are Productized

Scans directories and/or zip distributions for dependency files (e.g. JARs) and validates
that their file names contain a productization suffix (`-ibm-` or `.ibm-` by default). This is
a simple file-name-based check — it does not resolve Maven dependency trees.

Either source works on its own: configure `directories`, `distributions`, or both. The goal
fails if the configured sources together contain no matching files, reporting how many of each
it scanned and which `fileTypes` it was looking for.

#### Configuration

```xml
<plugin>
    <groupId>io.apitomy</groupId>
    <artifactId>apitomy-maven-plugin</artifactId>
    <version>${apitomy-maven-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>verify-dependencies</goal>
            </goals>
            <configuration>
                <!-- Optional: defaults to 'ibm'. -->
                <productizationModes>
                    <productizationMode>ibm</productizationMode>
                </productizationModes>
                <fileTypes>
                    <fileType>jar</fileType>
                </fileTypes>
                <directories>
                    <directory>${project.build.directory}/lib</directory>
                </directories>
                <distributions>
                    <distribution>${project.build.directory}/my-app.zip</distribution>
                </distributions>
                <ignoreFiles>
                    <ignoreFile>**/my-internal-lib-*.jar</ignoreFile>
                </ignoreFiles>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `fileTypes` | No | `jar` | List of file extensions to check. |
| `directories` | No | — | List of directories to scan for dependency files. |
| `distributions` | No | — | List of zip files to scan for dependency files. |
| `ignoreFiles` | No | — | List of wildcard patterns for files to ignore. |
| `productizationModes` | No | `ibm` | Productization flavours to accept: `ibm`, `redhat`. See [Productization Check](#productization-check). |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |

---

### `verify-maven-repository` — Verify an Offline Maven Repository

Validates an offline Maven repository (directory or `.zip` file) by resolving the full
transitive dependency tree for each matching artifact using Maven's Aether resolver. Checks
that all compile and runtime scoped dependencies are productized. This is useful for
validating PNC-produced offline Maven repositories before delivery.

A `.zip` may wrap the repository in one or more extra directories; the repository root is
located from the layout of the artifacts themselves, so a repository holding a single
top-level group directory is handled correctly.

#### Command Line Usage

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-maven-repository \
    -DrepositoryZip=/path/to/maven-repository.zip \
    -DartifactIncludes=apitomy-* \
    -Dverify.verbose=true
```

With a repository directory instead of a zip:

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-maven-repository \
    -DrepositoryDirectory=/path/to/maven-repository \
    -DartifactIncludes=apitomy-*
```

With excludes, ignores, and CSV output:

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-maven-repository \
    -DrepositoryZip=/path/to/maven-repository.zip \
    -DartifactIncludes=apitomy-* \
    -DartifactExcludes=apitomy-registry-utils-tests,*-parent \
    -DignoreGAVs=org.example:* \
    -DoutputCsv=/tmp/unproductized.csv
```

Validating a repository that still contains Red Hat-aligned artifacts alongside IBM ones:

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-maven-repository \
    -DrepositoryZip=/path/to/maven-repository.zip \
    -DartifactIncludes=apitomy-* \
    -DproductizationModes=ibm,redhat
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `repositoryDirectory` | No* | — | Path to an offline Maven repository directory. |
| `repositoryZip` | No* | — | Path to a `.zip` file containing an offline Maven repository. |
| `artifactIncludes` | Yes | — | Comma-separated wildcard patterns to match artifact IDs to validate (e.g. `apitomy-*`). |
| `artifactExcludes` | No | — | Comma-separated wildcard patterns to exclude artifact IDs from validation. |
| `ignoreGAVs` | No | — | Comma-separated `groupId:artifactId` patterns for dependencies to skip (e.g. `io.apitomy:*`). |
| `productizationModes` | No | `ibm` | Comma-separated productization flavours to accept: `ibm`, `redhat`. See [Productization Check](#productization-check). |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |
| `outputCsv` | No | — | Path to write a CSV file of unproductized dependencies. |

*Exactly one of `repositoryDirectory` or `repositoryZip` must be configured.

---

### `verify-project-dependencies` — Collect Current Project Dependencies

Checks that all compile and runtime scoped transitive dependencies of the current Maven
project are productized. Bound to the `package` phase by default, and typically activated via
a Maven profile during productized builds.

> **This goal never fails the build.** It logs warnings and writes its findings to
> `target/verify-project-dependencies/results.json`, leaving the pass/fail decision to
> [`verify-project-dependencies-report`](#verify-project-dependencies-report--report-on-collected-dependencies),
> which aggregates those files across the whole reactor. Configuring this goal on its own
> gives you warnings in the log and a green build. See [USAGE.md](USAGE.md) for the complete
> two-goal workflow.

#### Configuration

```xml
<profile>
    <id>productized</id>
    <build>
        <plugins>
            <plugin>
                <groupId>io.apitomy</groupId>
                <artifactId>apitomy-maven-plugin</artifactId>
                <version>${apitomy-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>verify-project-dependencies</goal>
                        </goals>
                        <configuration>
                            <!-- Optional: defaults to 'ibm'. List both during a migration. -->
                            <productizationModes>
                                <productizationMode>ibm</productizationMode>
                            </productizationModes>
                            <ignoreGAVs>
                                <ignoreGAV>org.example:*</ignoreGAV>
                            </ignoreGAVs>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

Then run the build far enough to reach the `package` phase, and invoke the report goal to get
a pass/fail verdict:

```bash
mvn clean package -Pproductized \
    io.apitomy:apitomy-maven-plugin:verify-project-dependencies-report
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `ignoreGAVs` | No | — | List of `groupId:artifactId` patterns for dependencies to skip. |
| `productizationModes` | No | `ibm` | Productization flavours to accept: `ibm`, `redhat`. See [Productization Check](#productization-check). |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |
| `outputCsv` | No | — | Path to write a CSV file of unproductized dependencies (per-module). |

---

### `verify-project-dependencies-report` — Report on Collected Dependencies

Aggregates the `results.json` files written by
[`verify-project-dependencies`](#verify-project-dependencies--collect-current-project-dependencies)
across every reactor module, and **fails the build** if any module has unaligned dependencies.
This is the goal that turns the collected findings into a verdict.

#### Command Line Usage

Run it after the reactor has been packaged, using its fully qualified goal name:

```bash
mvn clean package \
    io.apitomy:apitomy-maven-plugin:verify-project-dependencies-report
```

If the collect goal is configured inside a profile, activate that profile too (e.g.
`-Pproductized`).

#### Do Not Bind It in `<executions>`

This goal is an aggregator, so Maven runs it during the execution root's own build — and the
execution root is built *first*. Bound to a lifecycle phase it would run before the modules it
is meant to aggregate, so it would see no results. Rather than report an all-clear that means
nothing, the goal detects this and fails, naming the modules that had not been built yet.

It also fails when it finds no results at all, since that means nothing was verified.

#### Run Both Goals in One Maven Invocation

Each `results.json` is stamped with the Maven invocation that wrote it, and the report counts
only files from its own build. Results files outlive the build that wrote them, so a module
that did not run the collect goal this time — deselected with `-pl`, skipped by a profile, or
not rebuilt without `clean` — would otherwise contribute a stale verdict in either direction.
Leftovers are skipped with a warning naming the modules, and the report logs which modules it
did verify.

Running `mvn package` and then a separate `mvn ...:verify-project-dependencies-report`
therefore fails: the second invocation has nothing it can attribute to itself.

#### Output

A consolidated summary of every unaligned dependency across the reactor, plus an
`unaligned-dependencies.csv` in the root project's `target` directory. Counts are of distinct
dependencies — a dependency reached by several paths is one problem, shown with the shortest
path to it. See [USAGE.md](USAGE.md) for sample output and the full workflow.

---

### `verify-artifact-dependencies` — Verify a Single Artifact's Dependencies

Validates that all transitive dependencies of a single Maven artifact are productized. This
mojo does not require a Maven project and can be run directly from the command line. It
supports configurable remote and local repositories and can optionally bypass SSL certificate
verification.

#### Command Line Usage

Basic usage with Maven Central:

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apitomy:apitomy-registry-app:3.0.0-ibm-00002 \
    -DremoteRepositories=https://repo1.maven.org/maven2
```

With multiple remote repositories (using `id::url` format):

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apitomy:apitomy-registry-app:3.0.0-ibm-00002 \
    -DremoteRepositories=central::https://repo1.maven.org/maven2,jboss::https://repository.jboss.org/nexus/content/groups/public
```

With a local/offline repository (bypasses the `~/.m2` cache):

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apitomy:apitomy-registry-app:3.0.0-ibm-00002 \
    -DlocalRepository=/path/to/offline/maven-repository \
    -Dverify.verbose=true
```

With SSL verification disabled (for repos with self-signed certificates):

```bash
mvn io.apitomy:apitomy-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apitomy:apitomy-registry-app:3.0.0-ibm-00002 \
    -DremoteRepositories=https://my-internal-repo.example.com/maven2 \
    -Dinsecure=true
```

Validating a legacy Red Hat-productized artifact against Indy, by selecting the `redhat` mode:

```bash
rm -rf /tmp/empty-repo
mvn io.apitomy:apitomy-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apitomy:apitomy-registry-schema-resolver:3.1.6.redhat-00011 \
    -DproductizationModes=redhat \
    -DremoteRepositories=https://indy.corp.redhat.com/api/content/maven/group/static \
    -DlocalRepository=/tmp/empty-repo \
    -Dinsecure=true \
    -Dverify.verbose=true
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `artifact` | Yes | — | Artifact coordinates: `groupId:artifactId:version` or `groupId:artifactId:version:packaging`. |
| `remoteRepositories` | No | — | Comma-separated remote repository URLs. Use `id::url` format to specify an ID. |
| `localRepository` | No | — | Path to a local Maven repository directory (overrides `~/.m2/repository`). |
| `insecure` | No | `false` | Disable SSL certificate verification (`-Dinsecure=true`). |
| `ignoreGAVs` | No | — | Comma-separated `groupId:artifactId` patterns for dependencies to skip. |
| `productizationModes` | No | `ibm` | Comma-separated productization flavours to accept: `ibm`, `redhat`. See [Productization Check](#productization-check). |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |
| `outputCsv` | No | — | Path to write a CSV file of unproductized dependencies. |

---

### Common Concepts

#### Productization Check

All verification goals decide whether an artifact is productized by looking for a
productization suffix in its version. Which suffixes count is controlled by the
`productizationModes` parameter, which takes one or more flavours:

| Mode | Accepted suffixes | Example |
|------|-------------------|---------|
| `ibm` (default) | `-ibm-`, `.ibm-` | `3.0.0-ibm-00002`, `2.15.0.ibm-00004` |
| `redhat` | `-redhat-`, `.redhat-` | `3.0.0-redhat-00001`, `2.15.0.redhat-00001` |

An artifact is productized if it matches **any** configured mode, so a version such as
`3.0.0.Final` or `1.0.0-SNAPSHOT` is never productized.

**The default is `ibm`.** Builds that still produce Red Hat-aligned artifacts must say so:

```xml
<configuration>
    <productizationModes>
        <productizationMode>redhat</productizationMode>
    </productizationModes>
</configuration>
```

```bash
mvn ... -DproductizationModes=redhat
```

During the migration from Red Hat to IBM productization, configure both so a mixed
repository validates cleanly:

```bash
mvn ... -DproductizationModes=ibm,redhat
```

An unrecognised mode fails the build with a message listing the valid values.

> **Behaviour change in 1.5.0:** before 1.5.0 the check was hardcoded to the Red Hat suffixes.
> From 1.5.0 the default is `ibm`, so existing Red Hat builds must configure
> `productizationModes` explicitly or they will report every dependency as unaligned.

#### CSV Output

The `verify-maven-repository`, `verify-project-dependencies`, and
`verify-artifact-dependencies` goals support writing a CSV file of all unproductized
dependencies. The CSV has three columns (`groupId`, `artifactId`, `version`) and is
deduplicated and sorted.

Whenever `outputCsv` is configured the file is written, even by a run that finds nothing — in
which case it holds the header row alone. A clean run therefore replaces an earlier report
instead of leaving its violations on disk, so the file always describes the run that just
happened. The same applies to the `unaligned-dependencies.csv` written by
`verify-project-dependencies-report`.

#### Verbose Mode

All verification goals support verbose output via `-Dverify.verbose=true`. When enabled,
the goals log detailed information about dependency tree resolution, artifact matching, and
validation results.

## Links

- [Maven Central](https://central.sonatype.com/artifact/io.apitomy/apitomy-maven-plugin)
- [GitHub Repository](https://github.com/Apitomy/apitomy-maven-plugin)
- [Apitomy Website](https://www.apitomy.io)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute to this project.

## License

This project is licensed under the [Apache License 2.0](LICENSE).

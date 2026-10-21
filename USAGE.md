# Verifying Productized Dependencies

The `verify-project-dependencies` and `verify-project-dependencies-report` goals work together
to validate that all transitive dependencies in a multi-module Maven build are productized
(by default, have a `-ibm-` or `.ibm-` version suffix — see
[Productization Check](README.md#productization-check)).

## How It Works

The verification is split into two goals that run in separate phases:

- **`verify-project-dependencies`** (collect) — Runs per-module during the `package` phase.
  Resolves the full transitive dependency tree for each module and checks every compile/runtime
  dependency for the required productization suffix. Writes results to a JSON file under the
  module's own `target/verify-project-dependencies/` directory. This goal **never fails the
  build** on its own; it defers reporting to the aggregator goal.

- **`verify-project-dependencies-report`** (report) — An aggregator mojo that runs only on
  the execution root project. It reads all JSON result files produced by the collect goal
  across every reactor module and generates a consolidated report. If any module has unaligned
  dependencies, it **fails the build** with a single aggregate error message listing all
  violations. It also writes a de-duplicated `unaligned-dependencies.csv` to the root
  project's target directory.

## Recommended Workflow

Because the report goal is an aggregator (`aggregator = true`), it only executes on the root
project. This means it cannot be reliably configured alongside the collect goal in child module
POM files. The recommended workflow is:

1. **Configure the collect goal in each module's POM** (or in selected modules via a profile).
2. **Invoke the report goal on the command line** after the `package` phase completes.

### Step 1: Configure the Collect Goal

Add the `verify-project-dependencies` goal to your POM, typically in a profile that is only
activated during productized builds. Configure this in each module (or subset of modules)
whose dependencies you want to verify:

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
                        <id>verify-productized-deps</id>
                        <goals>
                            <goal>verify-project-dependencies</goal>
                        </goals>
                        <configuration>
                            <!-- Optional: 'ibm' is the default. -->
                            <productizationModes>
                                <productizationMode>ibm</productizationMode>
                            </productizationModes>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

To avoid repeating configuration across many modules, define the plugin in the root POM's
`<pluginManagement>` and activate it in selected child modules:

**Root POM (`pluginManagement`):**

```xml
<profile>
    <id>productized</id>
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>io.apitomy</groupId>
                    <artifactId>apitomy-maven-plugin</artifactId>
                    <version>${apitomy-maven-plugin.version}</version>
                    <executions>
                        <execution>
                            <id>verify-productized-deps</id>
                            <goals>
                                <goal>verify-project-dependencies</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</profile>
```

**Selected child modules:**

```xml
<profile>
    <id>productized</id>
    <build>
        <plugins>
            <plugin>
                <groupId>io.apitomy</groupId>
                <artifactId>apitomy-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</profile>
```

### Step 2: Invoke the Report Goal on the Command Line

Run the build with `package` (or later) to execute the collect goal in each configured module,
then invoke the report goal explicitly using its fully qualified goal name:

```bash
mvn clean package \
    io.apitomy:apitomy-maven-plugin:verify-project-dependencies-report
```

If the collect goal is configured inside a profile, activate that profile as well (e.g.
`-Pproductized`).

The report goal reads all `results.json` files from every reactor module's
`target/verify-project-dependencies/` directory and produces the aggregate report. If any
unaligned dependencies are found, the build fails.

You can also pin the plugin version in the command:

```bash
mvn clean package \
    io.apitomy:apitomy-maven-plugin:0.0.16-SNAPSHOT:verify-project-dependencies-report
```

### Safeguards Against a Silent Pass

A report that runs at the wrong moment, or with nothing to aggregate, would pass a build that was
never actually verified. The report goal therefore fails rather than staying quiet when:

- **It runs before the reactor is finished.** Adding `verify-project-dependencies-report` to an
  `<executions>` block binds it to a lifecycle phase of the *execution root*, which Maven builds
  first — so it would aggregate results that no module has written yet. The goal detects the
  reactor modules that have not been built and fails, naming them. Invoke it from the command line
  as shown above instead of binding it.
- **No module produced results.** If no `results.json` is found anywhere in the reactor, nothing was
  verified, and the goal fails pointing at the `verify-project-dependencies` configuration.

It also ignores results that are not from the current build. Each `results.json` is stamped with the
Maven invocation that wrote it, and results files survive in `target` across builds, so a module that
did not run the collect goal this time — deselected with `-pl`, skipped by a profile, or simply not
rebuilt without `clean` — would otherwise contribute a stale verdict in either direction: violations
that have since been fixed, or a clean result standing in for a module nobody checked. Leftovers are
skipped with a warning naming the modules, and the report logs which modules it did verify.

This is why both goals belong in **one** Maven invocation, as shown above. Running `mvn package` and
then a separate `mvn ...:verify-project-dependencies-report` leaves the report with nothing it can
attribute to its own build, and it fails rather than trusting the previous run's files.

## Configuration Options

The collect goal inherits the following parameters from the base verification mojo:

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `ignoreGAVs` | `ignoreGAVs` | (none) | List of `groupId:artifactId` patterns to ignore. Supports `*` wildcards. |
| `productizationModes` | `productizationModes` | `ibm` | Productization flavours to accept: `ibm`, `redhat`. |
| `verbose` | `verify.verbose` | `false` | Enable verbose dependency tree logging. |
| `outputCsv` | `outputCsv` | (none) | Path to write a CSV report of unaligned dependencies (per-module). |

Example with ignore patterns:

```xml
<execution>
    <id>verify-productized-deps</id>
    <goals>
        <goal>verify-project-dependencies</goal>
    </goals>
    <configuration>
        <ignoreGAVs>
            <ignoreGAV>org.example:*</ignoreGAV>
            <ignoreGAV>com.internal:internal-lib</ignoreGAV>
        </ignoreGAVs>
    </configuration>
</execution>
```

### Choosing Productization Modes

`productizationModes` selects which productization suffixes count as aligned. It defaults to
`ibm` (`-ibm-` and `.ibm-`); `redhat` accepts `-redhat-` and `.redhat-` instead. A dependency is
aligned if it matches any configured mode, so listing both is how a repository that still mixes
Red Hat and IBM artifacts validates cleanly during the migration:

```xml
<execution>
    <id>verify-productized-deps</id>
    <goals>
        <goal>verify-project-dependencies</goal>
    </goals>
    <configuration>
        <productizationModes>
            <productizationMode>ibm</productizationMode>
            <productizationMode>redhat</productizationMode>
        </productizationModes>
    </configuration>
</execution>
```

The same set can be supplied on the command line, which is useful for a one-off check without
editing the POM:

```bash
mvn clean package -Pproductized -DproductizationModes=ibm,redhat \
    io.apitomy:apitomy-maven-plugin:verify-project-dependencies-report
```

Note that the report goal reads the results the collect goal wrote, so the modes that matter are
the ones in effect when the collect goal ran. Set them in the same place.

## Output

### Per-Module Output (Collect Goal)

Each module that runs the collect goal logs its results during the `package` phase:

```
[INFO] Verifying dependencies for: io.apitomy:my-module:1.0.0
[INFO] === Project Dependency Verification Results ===
[INFO]   Unaligned dependencies: 3
[WARNING] Unaligned dependencies found in io.apitomy:my-module:1.0.0 (will report at end of reactor build).
[WARNING]   io.apitomy:my-module:1.0.0 (compile)
    -> com.example:unaligned-lib:1.0 (compile)
```

It also writes a JSON results file to `target/verify-project-dependencies/results.json`:

```json
{
  "module": {
    "groupId": "io.apitomy",
    "artifactId": "my-module",
    "version": "1.0.0",
    "unalignedDependencies": [
      {
        "groupId": "com.example",
        "artifactId": "unaligned-lib",
        "version": "1.0",
        "hierarchy": "io.apitomy:my-module:1.0.0 (compile)\n  -> com.example:unaligned-lib:1.0 (compile)"
      }
    ]
  }
}
```

### Aggregate Report (Report Goal)

The report goal produces a consolidated summary and fails the build if there are violations:

```
[ERROR] === Aggregate Dependency Verification Results ===
[ERROR] === Summary: 2 module(s) with failures, 5 unaligned dep(s) ===
[ERROR]
[ERROR] ==================================================================
[ERROR] - com.example:lib-a:1.0
[ERROR] - com.example:lib-b:2.0
[ERROR] ==================================================================
```

It also writes a de-duplicated `unaligned-dependencies.csv` to the root project's target
directory with columns: `groupId`, `artifactId`, `version`.

### CSV and JSON Files

Both the per-module JSON results and the aggregate CSV can be consumed by other tools or
CI/CD pipelines for further processing.

# Verifying Productized Dependencies

The `verify-project-dependencies` and `verify-project-dependencies-report` goals work together
to validate that all transitive dependencies in a multi-module Maven build are productized
(have a `-redhat-` or `.redhat-` version suffix).

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
                <groupId>io.apicurio</groupId>
                <artifactId>apicurio-maven-plugin</artifactId>
                <version>${apicurio-maven-plugin.version}</version>
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
                    <groupId>io.apicurio</groupId>
                    <artifactId>apicurio-maven-plugin</artifactId>
                    <version>${apicurio-maven-plugin.version}</version>
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
                <groupId>io.apicurio</groupId>
                <artifactId>apicurio-maven-plugin</artifactId>
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
    io.apicurio:apicurio-maven-plugin:verify-project-dependencies-report
```

If the collect goal is configured inside a profile, activate that profile as well (e.g.
`-Pproductized`).

The report goal reads all `results.json` files from every reactor module's
`target/verify-project-dependencies/` directory and produces the aggregate report. If any
unaligned dependencies are found, the build fails.

You can also pin the plugin version in the command:

```bash
mvn clean package \
    io.apicurio:apicurio-maven-plugin:0.0.16-SNAPSHOT:verify-project-dependencies-report
```

## Configuration Options

The collect goal inherits the following parameters from the base verification mojo:

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `ignoreGAVs` | `ignoreGAVs` | (none) | List of `groupId:artifactId` patterns to ignore. Supports `*` wildcards. |
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

## Output

### Per-Module Output (Collect Goal)

Each module that runs the collect goal logs its results during the `package` phase:

```
[INFO] Verifying dependencies for: io.apicurio:my-module:1.0.0
[INFO] === Project Dependency Verification Results ===
[INFO]   Unaligned dependencies: 3
[WARNING] Unaligned dependencies found in io.apicurio:my-module:1.0.0 (will report at end of reactor build).
[WARNING]   io.apicurio:my-module:1.0.0 (compile)
    -> com.example:unaligned-lib:1.0 (compile)
```

It also writes a JSON results file to `target/verify-project-dependencies/results.json`:

```json
{
  "module": {
    "groupId": "io.apicurio",
    "artifactId": "my-module",
    "version": "1.0.0",
    "unalignedDependencies": [
      {
        "groupId": "com.example",
        "artifactId": "unaligned-lib",
        "version": "1.0",
        "hierarchy": "io.apicurio:my-module:1.0.0 (compile)\n  -> com.example:unaligned-lib:1.0 (compile)"
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

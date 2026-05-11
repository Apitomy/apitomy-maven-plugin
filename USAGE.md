# Verifying Productized Dependencies

The `verify-project-dependencies` and `verify-project-dependencies-report` goals work together
to validate that all transitive dependencies in a multi-module Maven build are productized
(have a `-redhat-` or `.redhat-` version suffix).

## How It Works

The verification is split into two goals:

- **`verify-project-dependencies`** — Runs per-module during the `verify` phase. Resolves
  the full transitive dependency tree for each module and checks every dependency for the
  required productization suffix. Writes results to a JSON file under the reactor root's
  `target/verify-deps/` directory. This goal **never fails the build** on its own.

- **`verify-project-dependencies-report`** — Runs on every module but automatically skips
  until the last module in the reactor. On the last module, it reads all JSON result files
  produced by the collect goal and generates a consolidated report. If any module has
  unaligned dependencies, it fails the build with a single aggregate error message listing
  all violations across all modules.

## Basic Configuration

Add both goals to your POM, typically in a profile that is only activated during productized
builds:

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
                            <goal>verify-project-dependencies-report</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

Both goals default to the `verify` lifecycle phase. The collect goal runs in each module that
has it configured. The report goal runs once for the entire reactor because it uses Maven's
`aggregator = true` mechanism.

## Running on a Subset of Modules

In many multi-module projects, only certain modules need dependency verification. Both goals
can be configured together in the same execution block — the report goal automatically skips
on every module except the last one in the reactor.

To run verification on only specific modules, define the plugin in the root POM's
`<pluginManagement>` and activate it in the modules you want to verify:

**Root POM:**

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
                                <goal>verify-project-dependencies-report</goal>
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

Only modules that declare the plugin in `<plugins>` will run the collect goal. The report
goal runs on every module that has it configured but automatically skips until the last
reactor module, where it reads all collected results and reports.

## Configuration Options

Both goals inherit the following parameters from the base verification mojo:

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `ignoreGAVs` | `ignoreGAVs` | (none) | List of `groupId:artifactId` patterns to ignore. Supports `*` wildcards. |
| `verbose` | `verify.verbose` | `false` | Enable verbose dependency tree logging. |
| `outputCsv` | `outputCsv` | (none) | Path to write a CSV report of unaligned dependencies. |

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

### Per-Module Output

Each module that runs the collect goal logs its results immediately:

```
[INFO] Verifying dependencies for: io.apicurio:my-module:1.0.0
[INFO] === Project Dependency Verification Results ===
[INFO]   Unaligned dependencies: 3
[WARNING] Unaligned dependencies found in io.apicurio:my-module:1.0.0 (will report at end of reactor build).
[WARNING]   io.apicurio:my-module:1.0.0 (compile)
    -> com.example:unaligned-lib:1.0 (compile)
```

### Aggregate Report

At the end of the reactor build, the report goal produces a consolidated summary:

```
[ERROR] === Aggregate Dependency Verification Results ===
[ERROR]
[ERROR] --- io.apicurio:module-a:1.0.0 ---
[ERROR]   Unaligned dependencies:
[ERROR]     ...
[ERROR]
[ERROR] --- io.apicurio:module-b:1.0.0 ---
[ERROR]   Unaligned dependencies:
[ERROR]     ...
[ERROR]
[ERROR] === Summary: 2 module(s) with failures, 5 unaligned dep(s) ===
[ERROR]
[ERROR] ==================================================================
[ERROR] - com.example:lib-a:1.0
[ERROR] - com.example:lib-b:2.0
[ERROR] ==================================================================
```

### JSON Result Files

The collect goal writes a JSON file per module to `target/verify-deps/`:

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

These files can be consumed by other tools or CI/CD pipelines for further processing.

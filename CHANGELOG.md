# Changelog

Notable changes to the Apitomy Maven Plugin. Releases before 1.5.0 are described by the
auto-generated notes on the [releases page](https://github.com/Apitomy/apitomy-maven-plugin/releases).

## Unreleased

Nothing yet.

## [1.5.0](https://github.com/Apitomy/apitomy-maven-plugin/releases/tag/1.5.0) — 2026-10-21

### Upgrade notes

Read these two before upgrading from 1.0.0; both change what an existing build does.

- **Productization defaults to IBM.** The check previously hardcoded the Red Hat suffixes
  (`-redhat-`, `.redhat-`). It is now driven by the `productizationModes` parameter, which defaults to
  `ibm` (`-ibm-`, `.ibm-`). A build that still produces Red Hat-aligned artifacts must say so, or every
  dependency will be reported as unaligned:

  ```xml
  <productizationModes>
      <productizationMode>redhat</productizationMode>
  </productizationModes>
  ```

  List both modes to validate a repository that mixes the two during the migration. See
  [Productization Check](README.md#productization-check).

- **Builds that silently passed may now fail.** Several paths that reported success without verifying
  anything now fail instead. If a build starts failing on upgrade, it is reporting something that was
  previously hidden — check the message before changing the configuration.

### Added

- `productizationModes` parameter on all four verification goals, accepting `ibm` and `redhat`
  ([#90](https://github.com/Apitomy/apitomy-maven-plugin/issues/90)).
- Integration test suite covering the reactor workflows and mode configuration end to end through
  Maven.

### Fixed

- `verify-project-dependencies-report` no longer reports an all-clear when it runs before the reactor
  modules it aggregates. As an aggregator it executes during the execution root's build, which happens
  first, so bound to a phase it saw no results and passed. It now fails, naming the modules that had
  not been built, and also fails when it finds no results at all
  ([#77](https://github.com/Apitomy/apitomy-maven-plugin/issues/77)).
- The report no longer counts `results.json` files left by a previous build. Each file is stamped with
  the Maven invocation that wrote it, so stale findings cannot resurrect fixed violations or stand in
  for a module nobody checked. Collect and report must therefore run in one invocation
  ([#82](https://github.com/Apitomy/apitomy-maven-plugin/issues/82)).
- Unaligned dependencies are reported once each, with the shortest path to them, instead of once per
  route through the dependency graph. Per-module counts, the aggregate count and the CSV now agree
  ([#83](https://github.com/Apitomy/apitomy-maven-plugin/issues/83)).
- The CSV report is written on every run, with the header row alone when nothing is unaligned, so a
  clean run replaces an earlier report instead of leaving its violations on disk
  ([#84](https://github.com/Apitomy/apitomy-maven-plugin/issues/84)).
- `verify-dependencies` accepts a `distributions`-only configuration. The "found nothing" check ran
  before the distribution scan, so verifying a zip was impossible without also pointing the goal at a
  non-empty directory ([#79](https://github.com/Apitomy/apitomy-maven-plugin/issues/79)).
- `verify-maven-repository` locates the repository root from artifact coordinates rather than directory
  shape. A repository holding a single top-level group directory was mistaken for a wrapper, and
  resolution then failed for every artifact
  ([#80](https://github.com/Apitomy/apitomy-maven-plugin/issues/80)).

### Changed

- Minimum Java version raised from 11 to 17.
- Builds against `maven-core` and `maven-plugin-api` 3.10.0.
- `scripts/verify-artifact-dependencies.sh` gained a `--modes` option and now takes its default plugin
  version from the project POM.

### Documentation

- README documents `verify-project-dependencies-report`, and no longer implies that configuring the
  collect goal alone can fail a build — it cannot
  ([#78](https://github.com/Apitomy/apitomy-maven-plugin/issues/78)).
- README and USAGE.md cover productization modes, the single-invocation requirement, de-duplicated
  reporting and the CSV guarantee.

### Security

- `jackson-databind` updated to 2.22.4, which includes a security fix.

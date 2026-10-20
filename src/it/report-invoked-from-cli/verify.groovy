String log = new File(basedir, 'build.log').text

assert log.contains('Aggregate Dependency Verification Results') :
        "Expected the aggregate report to be produced"

assert log.contains('org.codehaus.plexus:plexus-utils:4.1.0') :
        "Expected the unaligned dependency from mod-a to be listed"

assert new File(basedir, 'target/unaligned-dependencies.csv').exists() :
        "Expected the aggregate CSV report in the root project's target directory"

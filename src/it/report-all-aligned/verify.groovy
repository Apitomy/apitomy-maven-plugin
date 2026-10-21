String log = new File(basedir, 'build.log').text

assert log.contains('Aggregating dependency verification results from 2 module(s)') :
        "Expected results from both reactor modules to be aggregated"

assert log.contains('All dependencies in all reactor modules are properly productized') :
        "Expected a clean reactor to be reported as productized"

File csv = new File(basedir, 'target/unaligned-dependencies.csv')
assert csv.exists() :
        "A clean reactor must still write the aggregate CSV, so a stale one cannot linger"
assert csv.readLines() == ['groupId,artifactId,version'] :
        "A clean reactor's CSV should hold the header and nothing else: ${csv.text}"

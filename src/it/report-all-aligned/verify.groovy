String log = new File(basedir, 'build.log').text

assert log.contains('Aggregating dependency verification results from 2 module(s)') :
        "Expected results from both reactor modules to be aggregated"

assert log.contains('All dependencies in all reactor modules are properly productized') :
        "Expected a clean reactor to be reported as productized"

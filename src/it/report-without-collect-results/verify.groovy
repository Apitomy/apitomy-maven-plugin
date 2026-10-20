String log = new File(basedir, 'build.log').text

assert log.contains('No dependency verification results found, so nothing was verified') :
        "Expected the report to fail when no module produced results"

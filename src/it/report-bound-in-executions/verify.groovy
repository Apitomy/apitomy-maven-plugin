String log = new File(basedir, 'build.log').text

assert log.contains("goal ran before 1 reactor module(s) had been built: mod-a") :
        "Expected the report goal to fail because mod-a had not been built yet"

assert !log.contains('All dependencies in all reactor modules are properly productized') :
        "The report must not claim an all-clear while modules are still unbuilt"

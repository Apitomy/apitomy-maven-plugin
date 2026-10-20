String log = new File(basedir, 'build.log').text

assert log.contains('Valid artifacts: 2') :
        "Both the IBM and the Red Hat artifact should be valid when both modes are configured"

assert log.contains('Invalid artifacts: 0') :
        "Nothing should be flagged when both modes are configured"

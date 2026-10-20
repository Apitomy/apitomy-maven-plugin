String log = new File(basedir, 'build.log').text

assert log.contains('legacy-lib-1.0.0-redhat-00001.txt') :
        "The Red Hat-suffixed artifact should be invalid under the default 'ibm' mode"

assert !log.contains('Invalid dependencies found: \nacme-core') :
        "The IBM-suffixed artifact should be accepted under the default mode"

assert log.contains('Valid artifacts: 1') :
        "Exactly one artifact (the IBM one) should be valid"

assert log.contains('Invalid artifacts: 1') :
        "Exactly one artifact (the Red Hat one) should be invalid"

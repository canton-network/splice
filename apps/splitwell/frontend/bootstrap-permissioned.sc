println("Waiting for DSO initialization...")
sv1.waitForInitialization()

println("Waiting for scan initialization...")
sv1Scan.waitForInitialization()

println("Waiting for validator initialization...")
sv1Validator.waitForInitialization()

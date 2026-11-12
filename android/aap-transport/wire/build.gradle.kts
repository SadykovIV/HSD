plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.1"
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.36.2")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    generateProtoTasks {
        all().configureEach {
            builtins { named("java") { option("lite") } }
        }
    }
}

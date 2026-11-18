plugins {
    id("kodakiko.jvm.tooling")
}

tasks.test {
    outputs.upToDateWhen { false }
}

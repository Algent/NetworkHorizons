plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test.configure {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// JMH benchmarks, never part of the mod jar: ./gradlew jmh [-PjmhArgs="RecordBenchmark -prof gc"]
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
}

dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks"
    classpath = jmh.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args(
        providers.gradleProperty("jmhArgs")
            .getOrElse("-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc")
            .split(" ")
            .filter { it.isNotBlank() })
}

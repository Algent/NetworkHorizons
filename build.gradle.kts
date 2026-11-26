plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test.configure {
    useJUnitPlatform()
    // private captures for CaptureRoundTripTest, opt-in
    systemProperty("nh.captures", findProperty("captureDir")?.toString() ?: "")
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Dev only, see launcher/README.md: ./gradlew runServer|runClient|runServer25|runClient25|test
// -PnettyOverride=4.0.56.Final [-PnettyNoUnsafe=false|unset]. The run tasks include the modern Java ones
// (runServer17/21/25, runClient17/21/25).
providers.gradleProperty("nettyOverride").orNull?.let { version ->
    val netty = configurations.detachedConfiguration(dependencies.create("io.netty:netty-all:$version"))
    val noUnsafe = providers.gradleProperty("nettyNoUnsafe").getOrElse("true")
    val runTask = Regex("run(Server|Client)(17|21|25)?")
    tasks.matching { runTask.matches(it.name) }.configureEach {
        this as JavaExec
        classpath = files(netty) + classpath
        if (noUnsafe != "unset") jvmArgs("-Dio.netty.noUnsafe=$noUnsafe")
    }
    tasks.test.configure {
        classpath = files(netty) + classpath
        if (noUnsafe != "unset") systemProperty("io.netty.noUnsafe", noUnsafe)
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
    // codec candidates for phase 4, benchmark only (same versions as CubicChunks1710)
    "jmhImplementation"("com.github.luben:zstd-jni:1.5.7-6")
    "jmhImplementation"("at.yawk.lz4:lz4-java:1.11.0")
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

// Codec comparison on captures: ./gradlew codecReport -PcodecArgs="--exclude [GalacticraftCore]#0 a.nhcap.gz"
tasks.register<JavaExec>("codecReport") {
    group = "verification"
    description = "Compares phase 4 codec candidates on NetworkHorizons captures"
    classpath = jmh.runtimeClasspath
    mainClass.set("dev.aruka.networkhorizons.codec.CodecReport")
    maxHeapSize = "2g"
    args(
        providers.gradleProperty("codecArgs")
            .getOrElse("")
            .split(" ")
            .filter { it.isNotBlank() })
}

// Chunk data through the phase 4 stream, on captures: ./gradlew chunkReport -PchunkArgs="a.nhcap.gz"
tasks.register<JavaExec>("chunkReport") {
    group = "verification"
    description = "Estimates sending chunk data through the phase 4 stream on NetworkHorizons captures"
    classpath = jmh.runtimeClasspath
    mainClass.set("dev.aruka.networkhorizons.codec.ChunkReport")
    maxHeapSize = "2g"
    args(
        providers.gradleProperty("chunkArgs")
            .getOrElse("")
            .split(" ")
            .filter { it.isNotBlank() })
}

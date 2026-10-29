plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test.configure {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

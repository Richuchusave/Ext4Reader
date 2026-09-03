plugins { kotlin("jvm") version "2.0.21" }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-blocks"))
    implementation(project(":core-partition"))
    testImplementation("junit:junit:4.13.2")
}

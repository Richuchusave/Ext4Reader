plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-blocks"))
    implementation(project(":core-partition"))
    testImplementation("junit:junit:4.13.2")
}

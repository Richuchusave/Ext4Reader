plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-blocks"))
    testImplementation("junit:junit:4.13.2")
}

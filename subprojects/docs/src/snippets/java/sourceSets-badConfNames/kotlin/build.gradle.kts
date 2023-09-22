plugins {
    `java-library`
}

configurations {
    val customCompileClasspath: Configuration by creating
}

sourceSets {
    val custom: SourceSet by creating
}

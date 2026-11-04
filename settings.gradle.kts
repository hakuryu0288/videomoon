pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")  // Maven Centralのミラー（混雑時の429対策）
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")  // Maven Centralのミラー（混雑時の429対策）
        mavenCentral()
    }
}
rootProject.name = "videomoon"
include(":app")

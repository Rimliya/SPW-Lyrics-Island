pluginManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*") } }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.Moriafly.spw-workshop-api") } }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule("com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("androidx\\..*") } }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "SPW-Lyrics-Island"

includeBuild("modules/media-runtime") {
    dependencySubstitution {
        substitute(module("io.github.gaboron.spwmedia:spw-media-spw-adapter"))
            .using(project(":spw-adapter"))
        substitute(module("io.github.gaboron.spwmedia:spw-media-runtime-core"))
            .using(project(":runtime-core"))
        substitute(module("io.github.gaboron.spwmedia:spw-media-processing"))
            .using(project(":providers:processing"))
    }
}

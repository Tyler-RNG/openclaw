pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    mavenLocal()
    // SpriteCore Kotlin SDK (animated multi-state avatars). Same coordinates the
    // wearable build uses; credentials come from ~/.gradle/gradle.properties.
    maven {
      name = "spriteCoreGitHubPackages"
      url = uri("https://maven.pkg.github.com/Tyler-RNG/sprite-core")
      credentials {
        username = providers.gradleProperty("gpr.user").orNull
          ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull
          ?: System.getenv("GITHUB_TOKEN")
      }
    }
  }
}

rootProject.name = "OpenClawNodeAndroid"
include(":app")
include(":benchmark")
include(":wear")
include(":wear-shared")

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
  }
}

rootProject.name = "OpenClawNodeAndroid"
include(":app")
include(":benchmark")
include(":displaykit")
project(":displaykit").projectDir = file("../shared/OpenClawDisplayKit")
include(":displaykit-android")
project(":displaykit-android").projectDir = file("../shared/OpenClawDisplayKitAndroid")

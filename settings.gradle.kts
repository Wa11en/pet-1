pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://ads.maven.yandex.net") { content { includeGroupByRegex("com\\.yandex.*"); includeGroupByRegex("io\\.appmetrica.*") } } } }
rootProject.name = "PrivateDiary"
include(":app")

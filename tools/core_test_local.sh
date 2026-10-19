#!/usr/bin/env bash
# 在云端环境里本地跑 core 单测（不需要 Android SDK）。
# 仓库根项目声明了 Android Gradle 插件，要从 dl.google.com 下载，云端环境访问不了；
# 这里临时生成一个只用 Kotlin JVM 插件的独立 Gradle 项目，直接编译仓库里的 core 源码和测试。
#
# 用法：tools/core_test_local.sh                         跑全部 core 测试
#       tools/core_test_local.sh --tests '*BankNotificationParserTest'
#       tools/core_test_local.sh --rerun-tasks           代码没变也强制重跑
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${LEI_CORE_TEST_DIR:-${TMPDIR:-/tmp}/lei-core-test}"
# 版本号跟 gradle/libs.versions.toml 保持一致
ver() { sed -n "s/^$1 = \"\(.*\)\"/\1/p" "$REPO/gradle/libs.versions.toml"; }

mkdir -p "$DIR/gradle/wrapper"
cp "$REPO/gradlew" "$DIR/"
cp "$REPO/gradle/wrapper/gradle-wrapper.jar" "$REPO/gradle/wrapper/gradle-wrapper.properties" "$DIR/gradle/wrapper/"

cat > "$DIR/settings.gradle.kts" <<'EOF'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "lei-core-test"
EOF

cat > "$DIR/build.gradle.kts" <<EOF
plugins {
    kotlin("jvm") version "$(ver kotlin)"
    kotlin("plugin.serialization") version "$(ver kotlin)"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$(ver serialization)")
    testImplementation("junit:junit:$(ver junit)")
}
sourceSets {
    main { kotlin.srcDir("$REPO/core/src/main/kotlin") }
    test { kotlin.srcDir("$REPO/core/src/test/kotlin") }
}
tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
EOF

cd "$DIR"
exec ./gradlew test --console=plain "$@"

plugins {
    `java-library`
    // paperweight-userdev：提供 Paper 26.1.2 完整开发环境（Mojang 映射 API + NMS）
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "io.github.randomgift"
version = "1.0.0"
description = "随机世界礼包插件 - 在地表随机生成可交互礼包实体"

java {
    // Paper 26.1.2 (Minecraft 26.1) 要求 JDK 25+ 运行 paperclip 子进程
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    // Paper 26.1.2 开发包（取该版本最新构建号）
    paperweight.paperDevBundle("26.1.2.build.+")
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        // 编译目标字节码为 Java 25（Paper 26.1.2 API 本身要求 JDK 25+）
        options.release = 25
    }
    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }
}
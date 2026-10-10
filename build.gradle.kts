plugins {
    id("java")
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.tellforgeai"
// Version is injected at build time by the release workflow
// (-PpluginVersion=x.y.z, taken from the git tag). Local builds keep a
// dev version so you never have to maintain a version number by hand.
version = providers.gradleProperty("pluginVersion")
    .orElse(System.getenv("GITHUB_REF_NAME")?.removePrefix("v") ?: "0.0.0-dev")
    .get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm("2025.1")
        pluginVerifier()
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    instrumentCode = false
    publishing {
        // Marketplace publishing token, provided via -PmarketplaceToken
        // (release workflow passes the JETBRAINS_TOKEN secret).
        token = providers.gradleProperty("marketplaceToken")
            .orElse(providers.environmentVariable("JETBRAINS_TOKEN"))
    }
    pluginConfiguration {
        id = "dev.tellforgeai"
        name = "TellForgeAI"
        version = project.version.toString()
        ideaVersion {
            // Target the IntelliJ Platform, not a specific IDE — the plugin
            // only uses platform-level APIs (tool window, settings, files),
            // so it runs in WebStorm, PhpStorm, IntelliJ IDEA, PyCharm, etc.
            sinceBuild = "251"
            untilBuild = provider { null }
        }
        description = """
            <p><strong>TellForgeAI</strong> is a bring-your-own-key AI coding assistant with two modes:
            <strong>Foretell</strong> (plan/read-only) and <strong>Forge</strong> (edit/build),
            powered by any OpenAI-compatible API.</p>

            <h3>Features</h3>
            <ul>
              <li>Tool-window chat with streaming responses and reasoning/thinking display</li>
              <li><strong>Multiple named provider profiles</strong> and a per-project provider/model dropdown</li>
              <li>Works with any OpenAI-compatible endpoint: OpenAI, OpenRouter, Ollama, LM Studio, Together, and more</li>
              <li>Optional second model for Foretell mode, per provider</li>
              <li>Agentic tool use: the model can list, read and search project files, and run terminal commands</li>
              <li>Reasoning-effort control and auto-detected context window with a usage meter</li>
              <li>Persisted chat history per project</li>
            </ul>

            <h3>Security</h3>
            <ul>
              <li>API keys are stored in the IDE <em>PasswordSafe</em> (macOS Keychain, Windows
              Credential Manager or the Linux keyring) — never in plain-text settings files</li>
              <li>In Foretell (plan) mode only read-only commands are permitted</li>
              <li>Terminal command execution requires explicit user approval by default
              (toggleable); pipes, redirects, command substitution and compound commands
              are always refused</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "Daniel Roberts"
            url = "https://github.com/djroberts/tell-forge-ai"
        }
    }
    pluginVerification {
        ides {
            // Pinned instead of recommended(): fewer / deterministic downloads,
            // so CI caching of the IDE distributions actually stays valid.
            // Covers the oldest supported build ("251") and a recent one.
            recommended()
        }
    }
}

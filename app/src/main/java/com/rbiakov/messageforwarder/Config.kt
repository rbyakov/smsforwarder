package com.rbiakov.messageforwarder

/**
 * All settings come from BuildConfig, injected by app/build.gradle.kts
 * from local.properties. No secrets live in the code or in Git.
 */
object Config {
    val smtpHost: String = BuildConfig.SMTP_HOST
    val smtpPort: String = BuildConfig.SMTP_PORT
    val smtpUser: String = BuildConfig.SMTP_USER
    val smtpPassword: String = BuildConfig.SMTP_PASSWORD
    val forwardTo: String = BuildConfig.FORWARD_TO

    val isConfigured: Boolean
        get() = smtpUser.isNotBlank() && smtpPassword.isNotBlank() && forwardTo.isNotBlank()
}

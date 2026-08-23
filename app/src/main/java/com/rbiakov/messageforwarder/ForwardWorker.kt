package com.rbiakov.messageforwarder

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Forwards a single SMS to email over SMTP.
 * Network errors → retry() with exponential backoff, auth error → failure().
 */
class ForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        val isTest = inputData.getBoolean(KEY_IS_TEST, false)

        if (!Config.isConfigured) {
            Log.e(TAG, "SMTP не настроен (пустые значения в BuildConfig) — письмо не отправлено")
            return@withContext Result.failure()
        }

        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val subject = if (isTest) "SMS Forwarder — тестовое письмо" else "SMS от $sender"
        val text = if (isTest) {
            "Тестовое письмо от SMS Forwarder.\nВремя: $time\nЕсли вы это читаете — SMTP настроен правильно."
        } else {
            "Отправитель: $sender\nВремя: $time\n\n$body"
        }

        try {
            sendMail(subject, text)
            Log.i(TAG, "Письмо отправлено: $subject")
            Result.success()
        } catch (e: AuthenticationFailedException) {
            Log.e(TAG, "Ошибка авторизации SMTP — проверьте пароль приложения", e)
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось отправить (попытка ${runAttemptCount + 1}), повторим", e)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private fun sendMail(subject: String, text: String) {
        val props = Properties().apply {
            put("mail.smtp.host", Config.smtpHost)
            put("mail.smtp.port", Config.smtpPort)
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(Config.smtpUser, Config.smtpPassword)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(Config.smtpUser))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(Config.forwardTo))
            setSubject(subject, "UTF-8")
            setText(text, "UTF-8")
        }
        Transport.send(message)
    }

    companion object {
        private const val TAG = "ForwardWorker"
        private const val MAX_ATTEMPTS = 10

        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_IS_TEST = "is_test"

        fun enqueue(context: Context, sender: String, body: String, timestamp: Long) {
            enqueueInternal(
                context,
                Data.Builder()
                    .putString(KEY_SENDER, sender)
                    .putString(KEY_BODY, body)
                    .putLong(KEY_TIMESTAMP, timestamp)
                    .build(),
            )
        }

        fun enqueueTest(context: Context) {
            enqueueInternal(context, Data.Builder().putBoolean(KEY_IS_TEST, true).build())
        }

        private fun enqueueInternal(context: Context, data: Data) {
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

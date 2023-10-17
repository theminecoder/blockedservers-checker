package me.theminecoder.blockedservers

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.send.WebhookEmbed
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.PrintWriter
import java.lang.IllegalStateException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration


interface Announcer {
    suspend fun configure()
    suspend fun postUpdate(server: Server, blocked: Boolean)
    suspend fun postFound(server: Server)
}

abstract class MicroblogAnnouncer : Announcer {

    final override suspend fun postUpdate(server: Server, blocked: Boolean) {
        postToNetwork("${server._id} (${server.hostname ?: "Hostname not yet known"}) has been ${if (blocked) "blocked" else "unblocked"} by Mojang!")
    }

    final override suspend fun postFound(server: Server) {
        postToNetwork("${server._id} has been identified as ${server.hostname}!")
    }

    abstract suspend fun postToNetwork(update: String)
}

object TwitterAnnouncer : MicroblogAnnouncer() {
    private val refreshMutex = Mutex()
    private val credFile = File("./twitter-creds.json")
    private lateinit var currentCreds: TwitterCreds
    private lateinit var clientCreds: TwitterClient

    private var delayedTweets = mutableListOf<String>()
    private var delayedUntil: Instant? = null

    override suspend fun configure() {
        clientCreds = TwitterClient(
            requireNotNull(env["TWITTER_CLIENT_ID"]) { "Missing TWITTER_CLIENT_ID" },
            requireNotNull(env["TWITTER_CLIENT_SECRET"]) { "Missing TWITTER_CLIENT_SECRET" }
        )

        log(credFile.absolutePath)
        var loadedCred =
            if (credFile.exists()) TwitterCreds(JSONObject(JSONTokener(credFile.readText())).getString("refreshToken")) else null

        if (loadedCred == null || !refreshToken(loadedCred.refreshToken)) {
            refreshMutex.withLock {
                err("No twitter creds found... spinning up oauth...")

                val callbackWaiter = Waiter()

                val stateString = UUID.randomUUID().toString()
                //cba with sha256 so its just state 2 lmao
                val codeVerifier = UUID.randomUUID().toString() + UUID.randomUUID().toString()

                HttpServer.create(InetSocketAddress(8080), 0).also {
                    it.createContext("/hello") { http ->
                        try {
                            http.responseHeaders.add("Content-type", "application/json")
                            http.sendResponseHeaders(200, 0)
                            val returnedCode = URI(http.requestURI.toString()).let {
                                val query_pairs: MutableMap<String, String> = mutableMapOf()
                                val query: String = it.query ?: ""
                                val pairs = query.split("&".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                                for (pair in pairs) {
                                    val idx = pair.indexOf("=")
                                    query_pairs[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                                }
                                query_pairs
                            }.let {
                                log(it.toString())
                                if (it["state"] != stateString) {
                                    return@let null
                                }
                                it["code"]
                            }

                            if (returnedCode == null) {
                                PrintWriter(http.responseBody).use { out ->
                                    out.println("????")
                                }
                                return@createContext
                            }

                            val tokenResponse = try {
                                runBlocking {
                                    httpClient.submitForm("https://api.twitter.com/2/oauth2/token", Parameters.build {
                                        append("code", returnedCode)
                                        append("grant_type", "authorization_code")
                                        append("redirect_uri", "http://localhost:8080/hello")
                                        append("code_verifier", codeVerifier)
                                    }) {
                                        basicAuth(clientCreds.clientId, clientCreds.clientSecret)
                                    }.also { log((it.request.content as FormDataContent).formData.toString()) }
                                        .body<String>()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                e.message
                            }

                            PrintWriter(http.responseBody).use { out ->
                                out.println(tokenResponse)
                            }

                            val tokenJson = JSONObject(JSONTokener(tokenResponse))
                            if (!tokenJson.has("refresh_token")) return@createContext err("Failed to get refresh token!")
                            currentCreds = TwitterCreds(
                                tokenJson.getString("refresh_token"),
                                tokenJson.getString("access_token"),
                                Date.from(Instant.now().plus(tokenJson.getInt("expires_in").seconds.toJavaDuration()))
                            )

                            it.stop(10)
                            callbackWaiter.doNotify()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            PrintWriter(http.responseBody).use { out ->
                                out.println(e.message)
                            }
                        }
                    }

                    it.start()
                }

                HttpUrl.Builder().apply {
                    scheme("https")
                    host("twitter.com")
                    encodedPath("/i/oauth2/authorize")

                    addQueryParameter("response_type", "code")
                    addQueryParameter("client_id", clientCreds.clientId)
                    addQueryParameter("redirect_uri", "http://localhost:8080/hello")
                    addQueryParameter("state", stateString)
                    addQueryParameter("code_challenge", codeVerifier)
                    addQueryParameter("code_challenge_method", "plain")
                    addQueryParameter(
                        "scope",
                        arrayOf("tweet.read", "tweet.write", "users.read", "offline.access").joinToString(" ")
                    )
                }.build().apply { log("Please login to twitter: $this") }

                callbackWaiter.doWait()
                saveCreds()
                log("Logged in :)")
            }
        } else log("Using saved twitter creds :)")

        GlobalScope.launch {
            delay(2.minutes) //offset from main checker
            while(isActive) {
                if(delayedUntil?.isBefore(Instant.now()) == true) {
                    delayedUntil = null
                    val oldDelay = delayedTweets
                    delayedTweets = mutableListOf()
                    oldDelay.forEach {
                        doPost(it)
                    }
                }
                delay(5.minutes)
            }
        }
    }

    private fun saveCreds() {
        credFile.writeText(
            JSONObject().put("refreshToken", currentCreds.refreshToken).put("accessToken", currentCreds.accessToken)
                .put("expires", currentCreds.expires.time).toString()
        )
    }

    private suspend fun refreshToken(refreshToken: String): Boolean {
        refreshMutex.withLock {

            log("Refreshing twitter token")
            val tokenResponse = try {
                runBlocking {
                    httpClient.submitForm("https://api.twitter.com/2/oauth2/token", Parameters.build {
                        append("refresh_token", refreshToken)
                        append("grant_type", "refresh_token")
                    }) {
                        basicAuth(clientCreds.clientId, clientCreds.clientSecret)
                    }.body<String>()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }

            val tokenJson = JSONObject(JSONTokener(tokenResponse))
            if (!tokenJson.has("refresh_token")) return false
            currentCreds = TwitterCreds(
                tokenJson.getString("refresh_token"),
                tokenJson.getString("access_token"),
                Date.from(Instant.now().plus(tokenJson.getInt("expires_in").seconds.toJavaDuration()))
            )
        }

        saveCreds()
        return true
    }

    override suspend fun postToNetwork(update: String) {
        if(delayedUntil != null) {
            delayedTweets.add(update)
        } else doPost(update)
    }

    private suspend fun doPost(update: String) {
        if (currentCreds.accessToken == null ||
            Instant.now().plus(1.hours.toJavaDuration()).isAfter(currentCreds.expires.toInstant())
        ) if (!refreshToken(currentCreds.refreshToken)) throw IllegalStateException()

        httpClient.post("https://api.twitter.com/2/tweets") {
            bearerAuth(currentCreds.accessToken!!)
            contentType(ContentType.Application.Json)
            setBody("""{"text":"$update"}""")
        }.also {
            if(it.status.value == 429) {
                delayedUntil = Instant.now().plus(1, ChronoUnit.DAYS)
                delayedTweets.add(update)
                return
            }
            if(!it.status.isSuccess()) {
                throw IllegalStateException(it.body<String>())
            }
        }
    }

    private data class TwitterCreds(
        val refreshToken: String,
        val accessToken: String? = null,
        val expires: Date = Date()
    )

    private data class TwitterClient(
        val clientId: String,
        val clientSecret: String
    )
}

object MastodonAnnouncer : MicroblogAnnouncer() {
    private lateinit var domain: String
    private lateinit var token: String

    override suspend fun configure() {
        domain = requireNotNull(env["MASTODON_DOMAIN"]) { "Missing MASTODON_DOMAIN" }
        token = requireNotNull(env["MASTODON_ACCESS_TOKEN"]) { "Missing MASTODON_ACCESS_TOKEN" }

        if(!httpClient.get("https://$domain/api/v1/accounts/verify_credentials"){
                bearerAuth(token)
        }.status.isSuccess()) throw IllegalStateException("Invalid mastodon key")
    }

    override suspend fun postToNetwork(update: String) {
        if(!httpClient.submitForm("https://$domain/api/v1/statuses", Parameters.build {
            append("status", update)
        }){
            bearerAuth(token)
        }.status.isSuccess()) throw IllegalStateException("Invalid mastodon key")
    }
}

object DiscordAnnouncer : Announcer {
    private lateinit var client: WebhookClient
    private var author =
        WebhookEmbed.EmbedAuthor("Check server status at ismyserverblocked.com", null, "https://ismyserverblocked.com")

    override suspend fun configure() {
        client = WebhookClient.withUrl(requireNotNull(env["DISCORD_HOOK_URL"]) { "Missing DISCORD_HOOK_URL" })
    }

    override suspend fun postUpdate(server: Server, blocked: Boolean) {
        postWebhook(WebhookEmbedBuilder().also {
            it.setTitle(WebhookEmbed.EmbedTitle("Server ${if (blocked) "Blocked" else "Unblocked"}", null))
            it.setColor(if (blocked) 13631488 else 3581519)
            it.addBasicFields(server)
        }.build())
    }

    override suspend fun postFound(server: Server) {
        postWebhook(WebhookEmbedBuilder().also {
            it.setTitle(WebhookEmbed.EmbedTitle("Server Hostname Found", null))
            it.addBasicFields(server)
        }.build())
    }

    private fun WebhookEmbedBuilder.addBasicFields(server: Server) {
        this.setAuthor(author)
        this.addField(WebhookEmbed.EmbedField(false, "Server Hostname", server.hostname ?: "Hostname not yet known"))
        this.addField(WebhookEmbed.EmbedField(false, "Server Hash", server._id))
    }

    private suspend fun postWebhook(message: WebhookEmbed) {
        client.send(message)
    }
}

@JvmInline
value class Waiter(private val channel: Channel<Unit> = Channel(0)) {

    suspend fun doWait() {
        channel.receive()
    }

    fun doNotify() {
        channel.trySend(Unit)
    }
}
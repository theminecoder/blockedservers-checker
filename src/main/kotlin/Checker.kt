package me.theminecoder.blockedservers;

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.setValue
import org.litote.kmongo.upsert
import java.net.URI
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

data class Server(
    val _id: String,
    val hostname: String? = null,
    val hostnameFound: Boolean = false,
    val currentlyBlocked: Boolean = false,
    val lastBlocked: Date = Date()
)

data class IPHash(
    val _id: String,
    val hostname: String
)

@Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")
fun log(message: String) = System.out.println("${Date()} - $message")
fun err(message: String) = System.err.println("${Date()} - $message")

val env = dotenv {
    ignoreIfMissing = true
}
val httpClient = HttpClient()
var lastKnownBlocked: List<String> = emptyList()
lateinit var mongoClient: CoroutineDatabase
lateinit var announcers: List<Announcer>

fun main() = runBlocking {
    Logger.getLogger("org.mongodb.driver").level = Level.OFF
    mongoClient =
        KMongo.createClient(requireNotNull(env["MONGO_URL"]) { "Missing MONGO_URL" }).coroutine.getDatabase(
            URI(env["MONGO_URL"]).path.removePrefix(
                "/"
            )
        ).also {
            try {
                it.listCollectionNames()
            } catch (e: Exception) {
                err("Can't connect to mongo!")
                e.printStackTrace()
                exitProcess(1)
            }
        }

    announcers = if (env["OFFLINE"]?.toBoolean() == false) {
        listOf(TwitterAnnouncer, MastodonAnnouncer, DiscordAnnouncer).map {
            it.configure()
            it
        }
    } else emptyList()

    if (announcers.size == 0) err("Running Offline (No Announcers)! Remember to manually post updates later!")

    while (isActive) {
        runCheck()
        delay(30.seconds)
    }
}

suspend fun runCheck() {
    log("Downloading block list...")
    val blockedRes: String = httpClient.get("https://sessionserver.mojang.com/blockedservers").body()
    val currentlyBlocked = blockedRes.lines().sorted()
    log("Got ${currentlyBlocked.size} blocked servers!")

    if (lastKnownBlocked.isEmpty()) {
        lastKnownBlocked = currentlyBlocked
        log("Loaded initial block list from mojang... Processing updates next check")
        return
    }
    if (lastKnownBlocked != currentlyBlocked) {
        lastKnownBlocked = currentlyBlocked
        log("Got updated blocked server list, holding for one check to make sure its not caching issues.")
        return
    }

    val blockedCount =
        mongoClient.getCollection<Server>("servers").countDocuments(Server::currentlyBlocked eq true)

    if (currentlyBlocked.size < (blockedCount / 2)) {
        err("Somehow received less then half the current blocked servers. Assuming a blank response was returned by accident.")
        return;
    }

    currentlyBlocked.forEach {
        val hostHash = mongoClient.getCollection<IPHash>("iphashes").findOne(IPHash::_id eq it)
        var server = mongoClient.getCollection<Server>("servers").findOne(Server::_id eq it) ?: Server(it)
        var updated = false

        if(server.hostname == null && hostHash != null) {
            server = server.copy(hostname = hostHash.hostname, hostnameFound = true)
            log("${server._id} HOSTNAME -> ${server.hostname}")
            announcers.forEach { it.postFound(server) }
            updated = true
        }

        if(server.hostname != null && !server.hostnameFound) server = server.copy(hostnameFound = true)

        if(!server.currentlyBlocked) {
            server = server.copy(
                currentlyBlocked = true,
                lastBlocked = Date()
            )
            log("${server._id} (${server.hostname ?: "???"}) -> BLOCKED")
            announcers.forEach { it.postUpdate(server, true) }
            updated = true
        }

        if(updated) {
            mongoClient.getCollection<Server>("servers").updateOne(Server::_id eq it, server, upsert())
        }
    }

    mongoClient.getCollection<Server>("servers").find(Server::currentlyBlocked eq true).batchSize(1000)
        .consumeEach { server ->
            if (server._id !in currentlyBlocked) {
                mongoClient.getCollection<Server>("servers")
                    .updateOne(Server::_id eq server._id, setValue(Server::currentlyBlocked, false))
                log("${server._id} (${server.hostname ?: "???"}) -> UNBLOCKED")
                announcers.forEach { it.postUpdate(server, false) }
            }
        }
}
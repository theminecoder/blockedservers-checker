var request = require('request'),
    mastodonapi = require('mastodon-api'),
    mongoose = require('mongoose'),
    findorcreate = require('mongoose-findorcreate'),
    twit = require('twit'),
    discord_url = process.env.DISCORD_HOOK_URL || '',
    twitter = new twit({
        consumer_key: process.env.TWITTER_CONSUMER_KEY,
        consumer_secret: process.env.TWITTER_CONSUMER_SECRET,
        access_token: process.env.TWITTER_ACCESS_TOKEN,
        access_token_secret: process.env.TWITTER_ACCESS_TOKEN_SECRET,
        timeout_ms: 1000 * 60
    }),
    mastodon = process.env.MASTODON_ACCESS_TOKEN ? new mastodonapi({
        access_token: process.env.MASTODON_ACCESS_TOKEN,
        timeout_ms: 1000 * 60,
        api_url: process.env.MASTODON_API_URL
    }) : null;

const offline = process.env.OFFLINE == "true";

mongoose.connect(process.env.MONGO_URL || 'mongodb://localhost/test', function (err) {
    if (err) {
        console.log(err);
        process.exit(1);
    }
});
var db = mongoose.connection;
db.on('error', console.error.bind(console, 'connection error:'));
db.once('open', function () {
    setInterval(updateServers, 1000 * 60 * 5);
    updateServers();
});

var toSend = [];

var ServerSchema = mongoose.Schema({
        _id: String,
        hostname: String,
        hostnameFound: Boolean,
        currentlyBlocked: Boolean,
        lastBlocked: Date
    }),
    IPHash = mongoose.model('IPHash', {
        _id: String,
        hostname: String
    });
ServerSchema.plugin(findorcreate);
var Server = mongoose.model('Server', ServerSchema)

function log(msg) {
    console.log(`${new Date()} - ${msg}`)
}

function err(msg) {
    console.error(`${new Date()} - ${msg}`)
}

function arraysEqual(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return false;
    if (a.length !== b.length) return false;

    var aCopy = a.map((x) => x);
    var bCopy = a.map((x) => x);

    a.sort();
    b.sort();

    for (var i = 0; i < a.length; ++i) {
        if (aCopy[i] !== bCopy[i]) return false;
    }
    return true;
}

var lastList;

var updateServers = function () {
    log("Downloading ban list...")
    request("https://sessionserver.mojang.com/blockedservers", function (err, res, body) {
        if (err || res.statusCode != 200) {
            console.error(err);
            return;
        }

        var serverHashes = body.split("\n").filter(function (serverHash) {
            return serverHash !== ""
        });
        log(`Got ${serverHashes.length} blocked servers!`);

        if(lastList != null) {
            var added = serverHashes.filter((it) => lastList.indexOf(it) == -1);
            var removed = lastList.filter((it) => serverHashes.indexOf(it) == -1);
            log(`Diff: Removed: ${removed} - Added: ${added}`)
        } else log("First server list, not diffing...")

        if(lastList == null || !arraysEqual(lastList, serverHashes)) {
            lastList = serverHashes;
            log("Got updated blocked server list, holding for one check to make sure its not caching issues.")
            return;
        }

        Server.count({}, function (err, currentCount) {
            if(err) {
                console.error(err);
                return;
            }

            if(serverHashes.length < (currentCount / 2)) {
                err("Somehow received less then half the current blocked servers. Assuming a blank response was returned by accident.")
                return;
            }

            serverHashes.map(function (serverHash) {
                IPHash.findOne({_id: serverHash}, function (err, ipHash) {
                    if (err) {
                        console.error(err);
                        return;
                    }
                    Server.findOrCreate({_id: serverHash}, {currentlyBlocked: false}, function (err, server) {
                        var updated = false;
                        if (err) {
                            console.error(err);
                            return;
                        }
                        if (ipHash && !server.hostname && ipHash.hostname) {
                            server.hostname = ipHash.hostname
                            postHostnameFoundTweet(server);
                            updated = true;
                        }
                        if (!server.currentlyBlocked) {
                            server.currentlyBlocked = true;
                            server.lastBlocked = Date.now();
                            postTweet(server, true);
                            updated = true;
                        }
                        if(updated) server.save(function (err) {
                            if (err) {
                                console.error(err);
                            }
                        });
                    });
                });
            });
            Server.find({currentlyBlocked: true}, function (err, servers) {
                if (err) {
                    console.error(err);
                    return;
                }
                servers.map(function (server) {
                    if (serverHashes.indexOf(server._id) < 0) {
                        server.currentlyBlocked = false;
                        server.save(function (err) {
                            if (err) {
                                console.error(err);
                            }
                        })
                        postTweet(server, false);
                    }
                });
            });
            Server.find({hostnameFound: true}, function (err, servers) {
                if (err) {
                    console.error(err);
                    return;
                }
                servers.map(function (server) {
                    server.hostnameFound = false;
                    server.save(function (err) {
                        if (err) {
                            console.error(err);
                        }
                        postHostnameFoundTweet(server);
                    });
                });
            });
        })
    });
}

function postTweet(server, blocked) {
    var msg = server._id + (server.hostname ? ' (' + server.hostname + ')' : ' (Hostname not yet known)') + ' has been ' + (blocked ? 'blocked' : 'unblocked') + ' by Mojang!'
    log("Sending: " + msg);
    postTweetPrivate(msg);
    if (discord_url.length > 0) {
        toSend.push({
            embeds: [{
                title: "Server " + (!blocked ? "Unb" : "B") + "locked",
                color: (blocked ? 13631488 : 3581519),
                fields: [{
                    name: "Server Hostname",
                    value: server.hostname ? server.hostname : "Hostname not yet known",
                    inline: true
                }, {
                    name: "Server Hash",
                    value: server._id,
                    inline: true
                }],
                provider: {
                    name: "Check server status at ismyserverblocked.com",
                    url: "https://ismyserverblocked.com"
                }
            }]
        });
    }
}

function postHostnameFoundTweet(server) {
    var msg = server._id + ' has been identified as ' + server.hostname + '!';
    log("Sending: " + msg)
    postTweetPrivate(msg);
    if(discord_url.length>0) {
        toSend.push({
            embeds: [{
                title: "Server Hostname Found",
                fields: [{
                    name: "Server Hostname",
                    value: server.hostname,
                    inline: true
                }, {
                    name: "Server Hash",
                    value: server._id,
                    inline: true
                }],
                provider: {
                    name: "Check server status at ismyserverblocked.com",
                    url: "https://ismyserverblocked.com"
                }
            }]
        });
    }
}

function postTweetPrivate(statusText) {
    if(offline) console.log("Tweet: "+statusText)
    else { 
        twitter.post('statuses/update', {status: statusText}).catch(function (err) {
            console.error(err);
        });
        if(mastodon) mastodon.post('statuses', {status: statusText}).catch(function (err) {
            console.error(err);
        });
    }
}

var doneAlert = true;
function uploadDiscord() {
    if(discord_url.length>0) {
        if (toSend.length > 0) {
            doneAlert = false;
            request.post(discord_url, {
                json: toSend[0]
            }, function (err, res, body) {
                if (res.statusCode != 204) {
                    err("Error sending, trying again in 1 seconds...", err || body);
                    setTimeout(uploadDiscord, 1000);
                    return;
                }

                toSend.shift();
                setTimeout(uploadDiscord, 1000);
            })
        } else {
            if (!doneAlert) {
                log("Finished processing discord messages");
                doneAlert = true;
            }
            setTimeout(uploadDiscord, 1000);
        }
    }
}

setTimeout(uploadDiscord, 1000);
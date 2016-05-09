var request = require('request'),
	mongoose = require('mongoose'),
	findorcreate = require('mongoose-findorcreate'),
	twit = require('twit'),
	twitter = new twit({
		consumer_key: process.env.TWITTER_CONSUMER_KEY,
		consumer_secret: process.env.TWITTER_CONSUMER_SECRET,
		access_token: process.env.TWITTER_ACCESS_TOKEN,
		access_token_secret: process.env.TWITTER_ACCESS_TOKEN_SECRET,
		timeout_ms: 1000*60
	});
	
mongoose.connect(process.env.MONGO_URL||'mongodb://localhost/test', function(err){
	if(err) {
		console.log(err);
		process.exit(1);
	}
});
var db = mongoose.connection;
db.on('error', console.error.bind(console, 'connection error:'));
db.once('open', function() {
	setInterval(updateServers, 1000*60*5);
	updateServers();
});

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

var updateServers = function() {
	console.log("Downloading ban list...")
	request("https://sessionserver.mojang.com/blockedservers", function(err, res, body) {
		if(err||res.statusCode!=200) {
			console.error(err);
			return;
		}
		var serverHashes = body.split("\n").filter(function(serverHash) {
			return serverHash!==""
		});
		console.log("Got "+serverHashes.length+" blocked servers!");
		serverHashes.map(function(serverHash) {
			IPHash.find({_id: serverHash}, function(err, ipHash) {
				if(err) {
					console.error(err);
					return;
				}
				Server.findOrCreate({_id: serverHash}, {currentlyBlocked: false}, function(err, server) {
					if(err) {
						console.error(err);
						return;
					}
					if(server.currentlyBlocked && server.hostname == null ) {
						console.log(serverHash +" = "+JSON.stringify(ipHash));
						if(ipHash && ipHash.hostname) {
							server.hostname = ipHash.hostname;
							console.log(server);
							server.save(function(err){
								if(err) {
									console.error(err);
								}
								postHostnameFoundTweet(server);
							});
						}
					}
					if(!server.currentlyBlocked) {
						server.currentlyBlocked = true;
						server.lastBlocked = Date.now();
						if(ipHash && !server.hostname) {
							server.hostname = ipHash.hostname
						}
						server.save(function(err){
							if(err) {
								console.error(err);
							}
							postTweet(server, true);
						});
					}
				});
			});
		});
		Server.find({currentlyBlocked: true}, function(err, servers) {
			if(err) {
				console.error(err);
				return;
			}
			servers.map(function(server){
				if(serverHashes.indexOf(server._id)<0) {
					server.currentlyBlocked = false;
					server.save(function(err) {
						if(err) {
							console.error(err);
						}
						postTweet(server, false);
					})
				}
			});
		});
		Server.find({hostnameFound: true}, function(err, servers) {
			if(err) {
				console.error(err);
				return;
			}
			servers.map(function(server) {
				server.hostnameFound = false;
				server.save(function(err){
					if(err) {
						console.error(err);
					}
					postHostnameFoundTweet(server);
				});
			});
		});
	});
}

function postTweet(server, blocked) {
	var status = server._id+(server.hostname?' ('+server.hostname+')':' (Hostname not yet known)')+' has been '+(blocked?'blocked':'unblocked')+' by Mojang!';
	postTweet(status);
}

function postHostnameFoundTweet(server) {
	var status = server._id+' has been identified as '+server.hostname+'!';
	postTweet(status);
}

function postTweet(statusText) {
	twitter.post('statuses/update', { status: statusText }).catch(function(err) {
		console.error(err);
	});
}
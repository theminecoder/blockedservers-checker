var request = require('request'),
	mongoose = require('mongoose'),
	findorcreate = require('mongoose-findorcreate');
	
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
		currentlyBlocked: Boolean,
		lastBlocked: Date
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
			Server.findOrCreate({_id: serverHash}, {currentlyBlocked: false}, function(err, server) {
				if(err) {
					console.error(err);
					return;
				}
				if(!server.currentlyBlocked) {
					server.currentlyBlocked = true;
					server.lastBlocked = Date.now();
					server.save();
				}
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
					server.save()
				}
			});
		});
	});
}
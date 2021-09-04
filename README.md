# scoop

scoop is a command line tool written in Scala that aims to quickly download a
full copy of the currently deployed [Discord] frontend client. On a decent
connection, scoop is able to download an entire client in about 20 seconds,
transferring about 70 MB in that period of time.

```sh
$ java -jar scoop.jar --branch canary
[100.00%] 3908 fetched + 99 skipped = 4007/4007 (69.8972 MB dl'ed)
```

It's worth clarifying that scoop downloads the frontend client, not the desktop
client. The latter is an Electron application that loads the former alongside
several other native modules that enable features such as Krisp noise
cancellation, accelerated audio encoding, and spellcheck.

## Usage

Prebuilt artifacts are currently unavailable. Make sure you have JDK 11
installed. JDK 8 or newer versions will probably work, but are completely
untested. If you don't already [sbt] installed, you can easily get it with
[Coursier]. Follow
[these instructions](https://get-coursier.io/docs/cli-installation) to install
the Coursier CLI (make sure it's available in your `PATH` when doing so).

Then, run these commands to install `scala`, `scalac`, `sbt`, and other tools:

```
cs setup
```

Alternatively, you can install sbt manually yourself.

After sbt is ready to go, run `sbt assembly` in the repository directory and a
JAR file will be present at `target/scala-2.13/scoop-assembly-0.0.0.jar` after
some time.

## Operation

scoop predominantly works by searching for asset hashes inside of the client
HTML and JavaScript and downloading everything it can find. Assets are pushed
onto a bounded queue, which are concurrently consumed by multiple downloader
tasks that stream the asset content directly to disk. The queue size and number
of concurrent downloaders can be tweaked through command line flags.

All output is stored in a folder created in the current working directory named
`scoop-output/`. Files that have already been downloaded are skipped, so make
sure to rename the folder to something more meaningful before running the
downloader again. In the future, scoop will give the output folder a more
descriptive name.

### Points of discovery

Operation begins by requesting the base page of the specified branch (by
default, stable). That URL looks like this: https://discord.com/channels/@me.
All referenced assets (JS, CSS, images) are downloaded. Then, the first script
is downloaded. At the time of writing, this first script is always the chunk
loader, which is responsible for conditionally loading small JavaScript chunks
at runtime depending on user preferences an actions. All chunks are
unconditionally downloaded.

Afterwards, the fourth script is downloaded, which is the entrypoint of the
client and is where the bulk of the JS rests. All referenced assets are then
downloaded.

[coursier]: https://get-coursier.io
[sbt]: https://www.scala-sbt.org
[discord]: https://discord.com

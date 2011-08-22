# A couple of akka based logback utilities

Implements a logback appender and layout for the following items:  

  * ActorAppender: A container appender, you add other appenders into this one and they will be executed asynchronously
  * HoptoadActorAppender: Builds a Hoptoad notice with optional integration in scalatra
  * Logstash redis appender: Builds a logstash json document and puts it on a redis queue
  
  
## Where to get?

This library is published to scala-tools.org

In sbt:

```sbt
libraryDependencies += "com.mojolly.logback" %% "logback-akka" % "0.3"
```

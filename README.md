# A couple of akka based logback utilities

Implements a logback appender and layout for the following items:  

  * ActorAppender: A container appender, you add other appenders into this one and they will be executed asynchronously
  * HoptoadActorAppender: Builds a Hoptoad notice with optional integration in scalatra
  * Logstash redis appender: Builds a logstash json document and puts it on a redis queue
  
This is pretty much a work in progress at this point.
If those three appenders are implemented then I'll publish it to scala-tools.
  

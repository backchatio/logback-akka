#!/bin/sh
java -Xms512m -Xmx1024m -XX:MaxPermSize=256m -XX:+CMSClassUnloadingEnabled -Dfile.encoding='UTF-8' -jar `dirname $0`/sbt-launch.jar "$@"

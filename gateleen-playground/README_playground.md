# gateleen-playground

This module provides a server example.

----------

Gateleen Playground can be executed in **cluster mode** with other verticles. To do that, follow this steps:

1. Create a clustered Vert.x instance using `Vertx.clusteredVertx(VertxOptions options, Handler<AsyncResult<Vertx>> resultHandler)`;
2. With the clustered Vert.x instance, deploy your verticles normally;
3. Compile the project in order to create the **jar**;
4. Run the **jar** with `java -jar gateleen-playground/target/playground.jar -cluster`;
5. Additionally, you may want to use your own cluster configuration file. Using one, execute the **jar** with `java -jar gateleen-playground/target/playground.jar -cp /directoryWhere/clusterFile/isIn -cluster`.

Following this steps, you can have Gateleen Playground in cluster mode with your verticles, making possible the communication using the **event bus** between them.
